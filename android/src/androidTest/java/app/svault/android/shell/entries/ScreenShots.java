package app.svault.android.shell.entries;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 條目畫面的驗收截圖與操作工具（只在儀器測試）。
 *
 * <p>條目畫面都有 {@code FLAG_SECURE}，{@code adb screencap} 拍到的是全黑（STACK §6）。這裡不關掉 {@code FLAG_SECURE}，
 * 而是在 App 自己的行程裡把每個視窗的 View 畫進 Bitmap（{@link View#draw}），再依視窗位置疊成一張；對話框的
 * 背景變暗依它的 {@code dimAmount} 補畫。系統列的圖示由系統畫，不在圖上。
 */
final class ScreenShots {

    private static final long SETTLE_MS = 700;

    private ScreenShots() {
    }

    static Instrumentation instr() {
        return InstrumentationRegistry.getInstrumentation();
    }

    /** @return 目前在前景的 Activity */
    static Activity resumed() {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Collection<Activity> a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
            if (a.isEmpty()) {
                throw new AssertionError("沒有前景的 Activity");
            }
            return a.iterator().next();
        }
        Activity[] found = new Activity[1];
        for (int i = 0; i < 50 && found[0] == null; i++) {
            instr().runOnMainSync(() -> {
                Collection<Activity> a = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED);
                found[0] = a.isEmpty() ? null : a.iterator().next();
            });
            if (found[0] == null) {
                SystemClock.sleep(100);
            }
        }
        if (found[0] == null) {
            throw new AssertionError("沒有前景的 Activity");
        }
        return found[0];
    }

    /** 等主執行緒閒下來、動畫跑完。 */
    static void settle() {
        instr().waitForIdleSync();
        // 測試期間螢幕不要關（關掉後 Activity 停止，LiveData 不再更新，截到的是空白）
        instr().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        SystemClock.sleep(SETTLE_MS);
        instr().waitForIdleSync();
    }

    /**
     * @param dir  輸出資料夾
     * @param name 檔名（不含副檔名）
     */
    static void shot(File dir, String name) {
        settle();
        instr().runOnMainSync(() -> {
            List<View> roots = rootViews();
            View base = roots.get(0);
            int w = base.getRootView().getResources().getDisplayMetrics().widthPixels;
            int h = base.getRootView().getResources().getDisplayMetrics().heightPixels;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            int[] at = new int[2];
            for (View r : roots) {
                if (r.getWidth() == 0 || r.getHeight() == 0 || r.getVisibility() != View.VISIBLE) {
                    continue;
                }
                ViewGroup.LayoutParams lp = r.getLayoutParams();
                if (lp instanceof WindowManager.LayoutParams wlp
                        && (wlp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                    c.drawColor(Color.argb(Math.round(wlp.dimAmount * 255), 0, 0, 0)); // token-check: allow 測試截圖重現系統畫的對話框背景變暗（不是 App 的顏色）
                }
                r.getLocationOnScreen(at);
                c.save();
                c.translate(at[0], at[1]);
                r.draw(c);
                c.restore();
            }
            dir.mkdirs();
            try (OutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    /** 所有視窗的根 View（由下往上）；和 Espresso 找視窗的方式相同。 */
    @SuppressWarnings({"unchecked", "PrivateApi", "DiscouragedPrivateApi"})
    static List<View> rootViews() {
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field f = global.getDeclaredField("mViews");
            f.setAccessible(true);
            return new ArrayList<>((List<View>) f.get(instance));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * 點最上層視窗中文字完全相同的 View（或它可點的上層）。
     *
     * @param text 文字
     */
    static void clickText(String text) {
        instr().runOnMainSync(() -> {
            List<View> roots = rootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View hit = findText(roots.get(i), text);
                if (hit != null) {
                    // 選單（PopupMenu）的項目在 ListView 裡：要用 performItemClick 才會選到
                    for (View c = hit; c.getParent() instanceof View; c = (View) c.getParent()) {
                        if (c.getParent() instanceof android.widget.AdapterView<?> list) {
                            int pos = list.getPositionForView(c);
                            list.performItemClick(c, pos, list.getItemIdAtPosition(pos));
                            return;
                        }
                    }
                    while (!hit.isClickable() && hit.getParent() instanceof View) {
                        hit = (View) hit.getParent();
                    }
                    hit.performClick();
                    return;
                }
            }
            StringBuilder where = new StringBuilder();
            for (View r : roots) {
                where.append(r.getClass().getSimpleName()).append(' ').append(r.getWidth()).append('x')
                        .append(r.getHeight()).append(' ');
                texts(r, where);
            }
            throw new AssertionError("找不到文字：" + text + "（視窗：" + where + "）");
        });
        settle();
    }

    /** @param text 文字 長按它（開群組選單） */
    static void longClickText(String text) {
        instr().runOnMainSync(() -> {
            View hit = findText(resumed().getWindow().getDecorView(), text);
            while (hit != null && !hit.isLongClickable() && hit.getParent() instanceof View) {
                hit = (View) hit.getParent();
            }
            if (hit == null) {
                throw new AssertionError("找不到文字：" + text);
            }
            hit.performLongClick();
        });
        settle();
    }

    /** @param id 前景 Activity 中的 View id 按下它 */
    static void click(int id) {
        instr().runOnMainSync(() -> resumed().findViewById(id).performClick());
        settle();
    }

    /**
     * @param id   前景 Activity 中的文字欄
     * @param text 要填入的文字
     */
    static void type(int id, String text) {
        instr().runOnMainSync(() -> ((TextView) resumed().findViewById(id)).setText(text));
        settle();
    }

    /** 按返回鍵。 */
    static void back() {
        instr().runOnMainSync(() -> ((ComponentActivity) resumed()).getOnBackPressedDispatcher().onBackPressed());
        settle();
    }

    private static void texts(View v, StringBuilder out) {
        if (v instanceof TextView t) {
            out.append('[').append(t.getText()).append(v.getVisibility() == View.VISIBLE ? "" : "(隱藏)").append(']');
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                texts(g.getChildAt(i), out);
            }
        }
    }

    private static View findText(View v, String text) {
        if (v.getVisibility() != View.VISIBLE) {
            return null;
        }
        if (v instanceof TextView t && text.contentEquals(t.getText())) {
            return v;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View found = findText(g.getChildAt(i), text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
