package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * 在 JVM 上跑 testFixtures 的資料驅動合併案例（AC-07-6；android 的儀器測試跑同一組）。每個案例一個動態測試，
 * 名稱就是案例名稱（標驗收條件編號）。
 */
class ThreeWayMergerCasesTest {

    @TestFactory
    Stream<DynamicTest> mergeCases() {
        return MergeCases.all().stream().map(c -> DynamicTest.dynamicTest(c.name(), () -> {
            List<String> failures = c.run();
            assertEquals(Collections.emptyList(), failures, c.name());
        }));
    }
}
