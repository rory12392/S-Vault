package app.svault.android.feature.importer;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
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
import app.svault.core.vault.KdfParams;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
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
 * 匯入精靈的儀器測試用的小工具（T-62；同 HLT 的 {@code HltTestSupport}：外殼測試的同類工具是套件私有、模組分支不能改）。
 * 假資料保管庫（記憶體裡的位置，走 App 正式的解鎖流程）、假資料 CSV（經測試 APK 的 {@code TestDocumentProvider}，
 * 和系統選擇器給的一樣是 {@code content://} 文件）、等條件成立、截圖（畫 View 樹：頁面有 {@code FLAG_SECURE}，系統截圖是黑的）。
 */
final class ImpTestSupport implements AutoCloseable {

    /** 測試 APK 的假文件提供者（{@code android/src/androidTest/AndroidManifest.xml}）。 */
    static final String DOCUMENTS = "content://app.svault.android.test.documents/";
    private static final long SETTLE_MS = 700;

    private final SvaultApplication app;
    private final List<String> documents = new ArrayList<>();
    private MemoryLocation location;
    private String vaultId;

    ImpTestSupport(SvaultApplication app) {
        this.app = app;
    }

    /**
     * 建立並解鎖一個假資料保管庫（上一個先上鎖）。
     *
     * @param drafts 既有的條目（假資料）
     * @param params 建立時的 KDF 參數（耗時量測用正式參數，其他用測試的低參數）
     */
    void unlockWith(List<EntryDraft> drafts, KdfParams params) throws Exception {
        app.vaultSession().lock();
        instr().waitForIdleSync();
        byte[] data;
        try (Vault v = new VaultCodec(params).create("匯入測試", TestVaults.password())) {
            VaultEditor editor = new VaultEditor(v, Clock.systemUTC());
            for (EntryDraft d : drafts) {
                editor.addEntry(v.groups().get(0).uuid(), d);
            }
            data = new VaultCodec(params).serialize(v);
            vaultId = v.id().toString();
        }
        location = new MemoryLocation("匯入測試.kdbx", data, false);
        app.vaultSession().unlock(location, TestVaults.password());
        instr().waitForIdleSync();
    }

    /**
     * 放一個假資料 CSV 到假文件提供者。
     *
     * @param name    檔名
     * @param content 內容（只放假帳密）
     * @return 給 {@code ImportWizardFragment#onPicked} 的文件
     */
    Uri document(String name, byte[] content) throws IOException {
        try (OutputStream out = app.getContentResolver().openOutputStream(
                Uri.parse(DOCUMENTS + name + "?create=true"), "wt")) {
            out.write(content);
        }
        documents.add(name);
        return Uri.parse(DOCUMENTS + name);
    }

    Uri document(String name, String content) throws IOException {
        return document(name, content.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 存檔次數（寫進記憶體裡的位置） */
    int writes() {
        return location.writes();
    }

    @Override
    public void close() {
        app.vaultSession().lock();
        instr().waitForIdleSync();
        // 假資料 CSV 也收掉（截斷成 0 位元組；提供者沒有刪除，檔案在測試 APK 的快取，解除安裝時一起刪）
        for (String name : documents) {
            try (OutputStream out = app.getContentResolver().openOutputStream(Uri.parse(DOCUMENTS + name), "wt")) {
                out.flush();
            } catch (IOException | RuntimeException e) {
                // 清理失敗不影響結果
            }
        }
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

    /** 等條件成立（每 50 毫秒看一次，最多 {@code seconds} 秒）。 */
    static void waitUntil(Supplier<Boolean> done, String what, int seconds) {
        for (int i = 0; i < seconds * 20; i++) {
            Boolean ok;
            try {
                ok = onMain(done);
            } catch (AssertionError | RuntimeException e) {
                // 換畫面途中沒有前景的 Activity
                ok = false;
            }
            if (Boolean.TRUE.equals(ok)) {
                return;
            }
            SystemClock.sleep(50);
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

    /** 等畫面穩定後把前景 Activity 的視窗畫成 PNG。 */
    static void shot(File dir, String name) {
        settle();
        shotNow(dir, name);
    }

    /** 不等動畫，立刻畫（背景工作進行中的 ProgressOverlay）。 */
    static void shotNow(File dir, String name) {
        instr().runOnMainSync(() -> {
            View root = resumed().getWindow().getDecorView();
            Bitmap out = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new Canvas(out));
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
