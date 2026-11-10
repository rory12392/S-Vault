package app.svault.android.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.core.sync.MergeCase;
import app.svault.core.sync.MergeCases;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 在測試手機上跑 core testFixtures 的資料驅動合併案例（AC-07-6：每個里程碑整合時以儀器測試跑一次；JVM 上是
 * {@code ThreeWayMergerCasesTest}）。手機上的 XML 解析是 Woodstox（Architecture §10），和 JVM 不同，所以合併後的
 * 序列化與重新開啟也要在手機上確認。全部案例跑完才報告，失敗訊息列出案例名稱（只有假資料）。
 */
@RunWith(AndroidJUnit4.class)
public class MergeCasesOnDeviceTest {

    @Test
    public void allMergeCasesPassOnDevice() {
        List<MergeCase> cases = MergeCases.all();
        assertTrue("沒有合併案例", !cases.isEmpty());
        List<String> failures = new ArrayList<>();
        for (MergeCase c : cases) {
            for (String f : c.run()) {
                failures.add(c.name() + "：" + f);
            }
        }
        assertEquals(Collections.<String>emptyList(), failures);
    }
}
