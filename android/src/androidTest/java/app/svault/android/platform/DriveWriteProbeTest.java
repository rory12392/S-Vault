package app.svault.android.platform;

import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.SvaultApplication;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 實測（T-11，人手驗證 ⑧）：Drive 文件提供者以 {@code "wt"} 寫入較短的內容後，立刻讀回拿到什麼、多久後才一致。
 * 只在 {@code -e driveprobe <Uri>} 時執行；Uri 必須是 App 已有長期權限的 Drive 測試檔（S-TD3），不是真實保管庫。
 * 結果印在 logcat（tag {@code SVaultProbe}）。
 */
@RunWith(AndroidJUnit4.class)
public class DriveWriteProbeTest {

    private static final String TAG = "SVaultProbe";

    /** {@code -e drivedump <Uri>}：把 Drive 上目前的內容存成 App 外部資料夾的 drive-dump.kdbx（給 keepassxc-cli 檢查）。 */
    @Test
    public void dumpCurrentContent() throws Exception {
        String uri = InstrumentationRegistry.getArguments().getString("drivedump");
        if (uri == null) {
            return;
        }
        SvaultApplication app = (SvaultApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        byte[] data = read(app.documents(), uri);
        java.nio.file.Files.write(new java.io.File(app.getExternalFilesDir(null), "drive-dump.kdbx").toPath(), data);
        log("dump len=" + data.length);
    }

    @Test
    public void shorterWriteThenReadBack() throws Exception {
        String uri = InstrumentationRegistry.getArguments().getString("driveprobe");
        if (uri == null) {
            // 一般的 connectedDebugAndroidTest 不跑（AGP 會把 Assume 的略過算成失敗）
            return;
        }
        SvaultApplication app = (SvaultApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        DocumentAccess docs = app.documents();
        VaultCodec codec = new VaultCodec();
        byte[] current = read(docs, uri);
        byte[] longer;
        byte[] shorter;
        try (Vault v = codec.open(current, TestVaults.password())) {
            VaultEditor ed = new VaultEditor(v, Clock.systemUTC());
            UUID root = v.groups().get(0).uuid();
            UUID pad = ed.addEntry(root, new EntryDraft("探針填充", "x".repeat(400), "", "", "y".repeat(400)));
            longer = codec.serialize(v);
            ed.recycleEntry(pad);
            ed.deleteEntryPermanently(pad);
            shorter = codec.serialize(v);
        }
        log("current=" + current.length + " longer=" + longer.length + " shorter=" + shorter.length);
        // 和 SaveCoordinator 一樣：先讀遠端（備份）→ 覆寫 → 讀回；兩次存檔之間隔不同的時間
        String gapArg = InstrumentationRegistry.getArguments().getString("gaps", "0,2000,5000,10000,20000");
        int[] gapsMs = Arrays.stream(gapArg.split(",")).mapToInt(Integer::parseInt).toArray();
        int mismatches = 0;
        for (int i = 0; i < gapsMs.length; i++) {
            byte[] data = i % 2 == 0 ? longer : shorter;
            SystemClock.sleep(gapsMs[i]);
            read(docs, uri);
            write(docs, uri, data);
            byte[] back = read(docs, uri);
            boolean ok = Arrays.equals(back, data);
            if (!ok) {
                mismatches++;
            }
            log("cycle " + i + " gap=" + gapsMs[i] + " wrote=" + data.length + " back=" + back.length
                    + " ok=" + ok + (ok ? "" : " startsWithNew=" + (back.length >= data.length
                    && Arrays.equals(Arrays.copyOf(back, data.length), data))));
            if (!ok) {
                report("after-mismatch", docs, uri, data, null);
            }
        }
        log("mismatches=" + mismatches);
        write(docs, uri, current);
    }

    private static void report(String label, DocumentAccess docs, String uri, byte[] expected, byte[] previous)
            throws Exception {
        long start = SystemClock.elapsedRealtime();
        for (int i = 0; i < 20; i++) {
            byte[] back = read(docs, uri);
            boolean same = Arrays.equals(back, expected);
            boolean prefix = back.length >= expected.length
                    && Arrays.equals(Arrays.copyOf(back, expected.length), expected);
            log(label + " t=" + (SystemClock.elapsedRealtime() - start) + "ms len=" + back.length
                    + " equal=" + same + " startsWithNew=" + prefix);
            if (same) {
                return;
            }
            SystemClock.sleep(250);
        }
    }

    /** logcat 會丟掉較早的行：同時寫到 App 外部資料夾的 drive-probe.txt。 */
    private static void log(String line) {
        Log.i(TAG, line);
        java.io.File f = new java.io.File(InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null), "drive-probe.txt");
        try (java.io.Writer w = new java.io.FileWriter(f, true)) {
            w.write(line + "\n");
        } catch (java.io.IOException e) {
            Log.w(TAG, "寫不進 drive-probe.txt");
        }
    }

    private static void write(DocumentAccess docs, String uri, byte[] data) throws Exception {
        try (OutputStream out = docs.openOverwrite(uri)) {
            out.write(data);
        }
    }

    private static byte[] read(DocumentAccess docs, String uri) throws Exception {
        try (InputStream in = docs.openRead(uri)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[64 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        }
    }
}
