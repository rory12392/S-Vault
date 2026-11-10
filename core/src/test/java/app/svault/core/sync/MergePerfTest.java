package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-07-7 在 JVM 上的對照（手機上的正式量測是儀器測試 {@code MergePerfOnDeviceTest}）：2,000 筆兩個版本的合併，
 * 含 100 個 64 KB 附件的 SHA-256，5 次的中位數在 1 秒內。
 */
class MergePerfTest {

    @Test
    @DisplayName("AC-07-7：合併 2,000 筆的兩個版本（含附件 SHA-256）1 秒內（JVM，5 次中位數）")
    void ac07_7_mergeTwoThousandEntriesUnderOneSecond() throws Exception {
        long[] millis = MergePerf.measure(5);
        long median = MergePerf.median(millis);
        System.out.println("AC-07-7 JVM 合併 2,000 筆（毫秒）：" + Arrays.toString(millis) + "，中位數 " + median);
        assertTrue(median < 1000, "中位數 " + median + " ms");
    }
}
