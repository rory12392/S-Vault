package app.svault.android.feature.health;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.svault.android.SvaultApplication;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.RecentStore;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 健檢頁的儀器測試用的小工具（T-53；同 GEN 的 {@code GenTestSupport}：外殼測試的同類工具是套件私有、模組分支不能改）。
 * 假資料保管庫（記憶體裡的位置，走 App 正式的解鎖流程，條目以 {@link VaultEditor} 寫進這份假資料）、等條件成立、
 * 截圖（畫 View 樹：頁面有 {@code FLAG_SECURE}，系統截圖是黑的）。
 */
final class HltTestSupport implements AutoCloseable {

    private static final long SETTLE_MS = 700;

    private final SvaultApplication app;
    private MemoryLocation location;
    private String vaultId;

    HltTestSupport(SvaultApplication app) {
        this.app = app;
    }

    /**
     * 建立並解鎖一個假資料保管庫（上一個先上鎖）。
     *
     * @param drafts 條目（假資料）
     * @return 名稱 → UUID
     */
    Map<String, UUID> unlockWith(List<EntryDraft> drafts) throws Exception {
        app.vaultSession().lock();
        instr().waitForIdleSync();
        Map<String, UUID> ids = new LinkedHashMap<>();
        byte[] data;
        try (Vault v = new VaultCodec(TestVaults.fastParams()).create("健檢", TestVaults.password())) {
            VaultEditor editor = new VaultEditor(v, Clock.systemUTC());
            for (EntryDraft d : drafts) {
                ids.put(d.title(), editor.addEntry(v.groups().get(0).uuid(), d));
            }
            data = new VaultCodec(TestVaults.fastParams()).serialize(v);
            vaultId = v.id().toString();
        }
        location = new MemoryLocation("健檢.kdbx", data, false);
        app.vaultSession().unlock(location, TestVaults.password());
        instr().waitForIdleSync();
        return ids;
    }

    @Override
    public void close() {
        app.vaultSession().lock();
        instr().waitForIdleSync();
        if (location == null) {
            return;
        }
        try {
            new RecentStore(app.localStore().recentFile()).remove(location.persistentId());
        } catch (IOException e) {
            // 清理失敗不影響結果
        }
        UUID id = UUID.fromString(vaultId);
        deleteTree(app.localStore().backupsDir().resolve(vaultId));
        deleteTree(app.localStore().baseFile(id, location.persistentId()));
        deleteTree(app.localStore().pendingFile(id, location.persistentId()));
        deleteTree(app.localStore().conflictFile(id, location.persistentId()));
    }

    static android.app.Instrumentation instr() {
        return InstrumentationRegistry.getInstrumentation();
    }

    /** @return 目前在前景的 Activity（在主執行緒呼叫） */
    static Activity resumed() {
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

    /** 等條件成立（每 100 毫秒看一次，最多 {@code seconds} 秒）。 */
    static void waitUntil(Supplier<Boolean> done, String what, int seconds) {
        for (int i = 0; i < seconds * 10; i++) {
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

    static void waitUntil(Supplier<Boolean> done, String what) {
        waitUntil(done, what, 10);
    }

    /** 等主執行緒閒下來、動畫跑完；測試期間螢幕不要關。 */
    static void settle() {
        instr().waitForIdleSync();
        instr().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        SystemClock.sleep(SETTLE_MS);
        instr().waitForIdleSync();
    }

    /**
     * 把前景 Activity 的視窗（有對話框時再疊上對話框的視窗）畫成 PNG。
     *
     * @param dir    輸出資料夾
     * @param name   檔名（不含副檔名）
     * @param dialog 要疊上去的對話框；{@code null} 時只畫 Activity
     */
    static void shot(File dir, String name, Dialog dialog) {
        settle();
        instr().runOnMainSync(() -> {
            View root = resumed().getWindow().getDecorView();
            Bitmap out = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            root.draw(canvas);
            if (dialog != null && dialog.getWindow() != null) {
                View top = dialog.getWindow().getDecorView();
                int[] at = new int[2];
                int[] base = new int[2];
                top.getLocationOnScreen(at);
                root.getLocationOnScreen(base);
                canvas.save();
                canvas.translate(at[0] - base[0], at[1] - base[1]);
                top.draw(canvas);
                canvas.restore();
            }
            dir.mkdirs();
            try (OutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
    }

    static List<View> descendants(View v) {
        List<View> out = new ArrayList<>();
        collect(v, out);
        return out;
    }

    private static void collect(View v, List<View> out) {
        out.add(v);
        if (v instanceof android.view.ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
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
