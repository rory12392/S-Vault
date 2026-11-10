package app.svault.android.feature.biometric;

import android.app.Activity;
import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.svault.android.SvaultApplication;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.RecentStore;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * BIO 儀器測試的畫面工具（T-71）。外殼的同類工具（{@code shell.entries.ScreenShots}、{@code TestVaultFiles}）是那個
 * 套件私有的，模組分支不改外殼的測試，所以這裡放一份只含本模組要用的部分：在 App 行程裡把各視窗畫成 PNG（設定頁有
 * {@code FLAG_SECURE}，{@code adb screencap} 是全黑）、等條件成立、找文字，以及解鎖一個記憶體裡的假資料保管庫
 * （密碼 probe1234）並在結束時清掉留在 App 本機資料的紀錄。
 */
final class BioScreens {

    private static final long SETTLE_MS = 700;

    private BioScreens() {
    }

    static Instrumentation instr() {
        return InstrumentationRegistry.getInstrumentation();
    }

    static SvaultApplication app() {
        return (SvaultApplication) instr().getTargetContext().getApplicationContext();
    }

    /** @return 目前在前景的 Activity；沒有時丟 {@link AssertionError} */
    static Activity resumed() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return onMain(BioScreens::resumed);
        }
        Collection<Activity> a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
        if (a.isEmpty()) {
            throw new AssertionError("沒有前景的 Activity");
        }
        return a.iterator().next();
    }

    static <T> T onMain(Supplier<T> get) {
        Object[] box = new Object[1];
        instr().runOnMainSync(() -> box[0] = get.get());
        @SuppressWarnings("unchecked")
        T t = (T) box[0];
        return t;
    }

    static void run(Runnable r) {
        instr().runOnMainSync(r);
    }

    /** 等主執行緒閒下來、動畫跑完；畫面保持亮著（關掉後截到的是空白）。 */
    static void settle() {
        instr().waitForIdleSync();
        run(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        SystemClock.sleep(SETTLE_MS);
        instr().waitForIdleSync();
    }

    static void waitUntil(Supplier<Boolean> done, String what) {
        for (int i = 0; i < 100; i++) {
            Boolean ok;
            try {
                ok = onMain(done);
            } catch (AssertionError | RuntimeException e) {
                // 換畫面途中沒有前景的 Activity
                ok = false;
            }
            if (Boolean.TRUE.equals(ok)) {
                settle();
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("等不到：" + what);
    }

    /**
     * @param dir  輸出資料夾；{@code null} 時不截圖（一般的 connectedDebugAndroidTest）
     * @param name 檔名（不含副檔名）
     */
    static void shot(File dir, String name) {
        if (dir == null) {
            return;
        }
        settle();
        run(() -> {
            List<View> roots = rootViews();
            View base = roots.get(0);
            int w = base.getResources().getDisplayMetrics().widthPixels;
            int h = base.getResources().getDisplayMetrics().heightPixels;
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

    /** @return 任何視窗（含 Snackbar、對話框）中有文字完全相同、看得到的 View 時為 {@code true}（主執行緒） */
    static boolean hasText(String text) {
        for (View r : rootViews()) {
            if (findText(r, text) != null) {
                return true;
            }
        }
        return false;
    }

    /** 點最上層視窗中文字完全相同的 View（或它可點的上層）。 */
    static void clickText(String text) {
        run(() -> {
            List<View> roots = rootViews();
            for (int i = roots.size() - 1; i >= 0; i--) {
                View hit = findText(roots.get(i), text);
                if (hit != null) {
                    while (!hit.isClickable() && hit.getParent() instanceof View) {
                        hit = (View) hit.getParent();
                    }
                    hit.performClick();
                    return;
                }
            }
            throw new AssertionError("找不到文字：" + text);
        });
        settle();
    }

    /** 按系統的返回鍵（生物辨識提示框是系統畫面，App 內送的按鍵到不了）。 */
    static void pressBack() {
        try {
            instr().getUiAutomation().executeShellCommand("input keyevent 4").close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        SystemClock.sleep(SETTLE_MS);
        settle();
    }

    static View findText(View v, String text) {
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

    /** 解鎖一個記憶體裡的假資料保管庫（設定頁在 SecureActivity 裡，沒解鎖會導回解鎖畫面）；{@link #close} 清掉。 */
    static final class TestVault implements AutoCloseable {

        private final SvaultApplication app;
        private final MemoryLocation location;
        private final UUID id;

        TestVault(SvaultApplication app, String name) throws Exception {
            this.app = app;
            app.vaultSession().lock();
            instr().waitForIdleSync();
            byte[] data;
            VaultCodec codec = new VaultCodec(TestVaults.fastParams());
            try (Vault v = codec.create(name, TestVaults.password())) {
                data = codec.serialize(v);
                id = v.id();
            }
            location = new MemoryLocation(name + ".kdbx", data, false);
            app.vaultSession().unlock(location, TestVaults.password());
            instr().waitForIdleSync();
        }

        /** @return 保管庫 ID（小寫 UUID，同 {@code ModuleHost.unlockedVaultId()}） */
        String id() {
            return id.toString();
        }

        @Override
        public void close() {
            app.vaultSession().lock();
            instr().waitForIdleSync();
            try {
                new RecentStore(app.localStore().recentFile()).remove(location.persistentId());
            } catch (IOException e) {
                // 清理失敗不影響結果
            }
            deleteTree(app.localStore().backupsDir().resolve(id.toString()));
            deleteTree(app.localStore().baseFile(id, location.persistentId()));
            deleteTree(app.localStore().pendingFile(id, location.persistentId()));
            deleteTree(app.localStore().conflictFile(id, location.persistentId()));
        }

        private static void deleteTree(Path dir) {
            if (!Files.exists(dir)) {
                return;
            }
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                // 清理失敗不影響結果
            }
        }
    }
}
