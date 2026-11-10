package app.svault.android.feature.generator;

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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * 產生器頁的儀器測試用的小工具（T-42）。外殼測試的同類工具（{@code shell.entries.ScreenShots}、{@code TestVaultFiles}）是
 * 套件私有、模組分支不能改，所以這裡只留本模組用得到的部分：假資料保管庫（記憶體裡的位置，走 App 正式的解鎖流程）、
 * 等條件成立、截圖（畫 View 樹：頁面有 {@code FLAG_SECURE}，系統截圖是黑的）。
 */
final class GenTestSupport implements AutoCloseable {

    private static final long SETTLE_MS = 700;

    private final SvaultApplication app;
    private MemoryLocation location;
    private String vaultId;

    GenTestSupport(SvaultApplication app) {
        this.app = app;
    }

    /** 建立並解鎖一個空的假資料保管庫（上一個先上鎖）。 */
    void unlockEmptyVault() throws Exception {
        unlock(null);
    }

    /**
     * 建立並解鎖一個有一筆條目的假資料保管庫（編輯畫面的「產生」用，T-44）。
     *
     * @param password 條目原本的密碼（假資料）
     * @return 條目的 UUID
     */
    UUID unlockWithEntry(String password) throws Exception {
        return unlock(new EntryDraft("GitHub", "rory-dev", password, "https://github.com/login", ""));
    }

    private UUID unlock(EntryDraft draft) throws Exception {
        app.vaultSession().lock();
        instr().waitForIdleSync();
        byte[] data;
        UUID entry = null;
        try (Vault v = new VaultCodec(TestVaults.fastParams()).create("產生器", TestVaults.password())) {
            if (draft != null) {
                entry = new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(), draft);
            }
            data = new VaultCodec(TestVaults.fastParams()).serialize(v);
            vaultId = v.id().toString();
        }
        location = new MemoryLocation("產生器.kdbx", data, false);
        app.vaultSession().unlock(location, TestVaults.password());
        instr().waitForIdleSync();
        return entry;
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
     * 把前景 Activity 的視窗畫成 PNG（頁面只有一個視窗；Snackbar 在同一個視窗裡）。
     *
     * @param dir  輸出資料夾
     * @param name 檔名（不含副檔名）
     */
    static void shot(File dir, String name) {
        shot(dir, name, null);
    }

    /**
     * 把前景 Activity 的視窗、再把對話框的視窗疊在上面畫成 PNG（底部 sheet 是另一個視窗，也有 {@code FLAG_SECURE}）。
     * 系統在對話框後面畫的暗色遮罩不在 View 樹裡，截圖上沒有（token 檢查也不准在程式裡建立顏色）。
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
