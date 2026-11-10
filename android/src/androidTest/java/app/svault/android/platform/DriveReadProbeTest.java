package app.svault.android.platform;

import android.os.SystemClock;
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.SvaultApplication;
import app.svault.core.storage.Hashes;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.time.Instant;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 實測（T-14，未解問題第 1 條）：PC 透過 Drive 電腦版改了檔案之後，手機經檔案選擇器（SAF）多久讀得到新的內容
 * （「回到前景自動更新」看得到 PC 的修改要等多久）。每 {@code -e interval} 毫秒（預設 2000）讀一次、算 SHA-256，
 * 內容變了就記下時間並結束；最多等 {@code -e watchsec} 秒（預設 600）。
 *
 * <p>只在 {@code -e drivewatch <Uri>} 時執行；Uri 必須是 App 已有長期權限的 Drive 測試檔（S-TD3），不是真實保管庫。
 * 只讀不寫。結果印在 logcat（tag {@code SVaultProbe}）並寫到 App 外部資料夾的 {@code drive-watch.txt}（含手機的
 * 牆上時間，和 PC 寫檔的時間對照）。
 */
@RunWith(AndroidJUnit4.class)
public class DriveReadProbeTest {

    private static final String TAG = "SVaultProbe";

    @Test
    public void watchUntilRemoteChanges() throws Exception {
        String uri = InstrumentationRegistry.getArguments().getString("drivewatch");
        if (uri == null) {
            // 一般的 connectedDebugAndroidTest 不跑（AGP 會把 Assume 的略過算成失敗）
            return;
        }
        long interval = Long.parseLong(InstrumentationRegistry.getArguments().getString("interval", "2000"));
        long watchMs = Long.parseLong(InstrumentationRegistry.getArguments().getString("watchsec", "600")) * 1000;
        SvaultApplication app = (SvaultApplication) InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext();
        DocumentAccess docs = app.documents();
        String first = Hashes.sha256(read(docs, uri));
        long start = SystemClock.elapsedRealtime();
        log("start " + Instant.now() + " hash=" + first.substring(0, 12));
        while (SystemClock.elapsedRealtime() - start < watchMs) {
            SystemClock.sleep(interval);
            byte[] data = read(docs, uri);
            String now = Hashes.sha256(data);
            if (!now.equals(first)) {
                log("changed " + Instant.now() + " after=" + (SystemClock.elapsedRealtime() - start) + "ms len="
                        + data.length + " hash=" + now.substring(0, 12));
                return;
            }
        }
        log("unchanged after " + watchMs + "ms " + Instant.now());
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

    /** logcat 會丟掉較早的行：同時寫到 App 外部資料夾的 drive-watch.txt。 */
    private static void log(String line) {
        Log.i(TAG, line);
        File f = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getExternalFilesDir(null),
                "drive-watch.txt");
        try (Writer w = new FileWriter(f, true)) {
            w.write(line + "\n");
        } catch (IOException e) {
            Log.w(TAG, "寫不進 drive-watch.txt");
        }
    }
}
