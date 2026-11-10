package app.svault.android.sync;

import static org.junit.Assert.assertTrue;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.core.sync.MergePerf;
import java.util.Arrays;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * AC-07-7：在測試手機（Pixel 9 Pro）上合併 2,000 筆條目的兩個版本，1 秒內完成。量快照（含 100 個 64 KB 附件的
 * SHA-256）＋三方合併＋套用，5 次取中位數（STACK §6 效能）。結果印在 logcat（tag {@code SVaultPerf}），由整合紀錄抄進
 * {@code metrics.csv}。JVM 上的對照是 core 的 {@code MergePerfTest}。
 */
@RunWith(AndroidJUnit4.class)
public class MergePerfOnDeviceTest {

    private static final String TAG = "SVaultPerf";

    @Test
    public void ac07_7_mergeTwoThousandEntriesUnderOneSecond() throws Exception {
        // 第一次載入類別與 JIT 預熱不算：先跑一次丟掉
        MergePerf.measure(1);
        long[] millis = MergePerf.measure(5);
        long median = MergePerf.median(millis);
        Log.i(TAG, "AC-07-7 合併 2,000 筆（毫秒）：" + Arrays.toString(millis) + "，中位數 " + median
                + "；最後一次 " + MergePerf.lastPhases());
        assertTrue("中位數 " + median + " ms", median < 1000);
    }
}
