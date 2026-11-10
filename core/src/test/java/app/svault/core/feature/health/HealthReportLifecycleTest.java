package app.svault.core.feature.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.feature.health.HealthReportModel.Analysis;
import app.svault.core.feature.health.HealthReportModel.Breach;
import app.svault.core.feature.health.HealthReportModel.Row;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 健檢報告頁的生命週期（T-52、T-53；T-117 合進 core）：上鎖、還原備份、關頁時取消、畫面重建時換監聽，以及日誌不帶機密
 * （規格 §1、CONVENTIONS §11）。
 */
class HealthReportLifecycleTest extends HealthModelFixture {

    /** 第一次取用分析器時卡住，直到 {@code release} 放行（模擬分析中途發生事情）。 */
    private static Supplier<WeakReuseAnalyzer> blockedUntil(CountDownLatch release) {
        return () -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return analyzer;
        };
    }

    @Test
    @DisplayName("已上鎖時不分析、不連線")
    void lockedDoesNothing() throws Exception {
        HealthReportModel m = model(() -> analyzer);
        assertFalse(onUi(m::analyze));
        assertFalse(onUi(m::checkBreach));
        assertEquals(Analysis.IDLE, onUi(m::analysis));
        assertEquals(Breach.NOT_CHECKED, onUi(m::breach));
        assertEquals(List.of(), hibp.paths());
    }

    @Test
    @DisplayName("分析途中上鎖時丟掉結果、回到 IDLE（CONVENTIONS §11：顯示前再看一次 isOpen）")
    void lockDuringAnalysisDropsResult() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        access.open().publish(data(bank, mail, shop));
        HealthReportModel m = model(blockedUntil(release));
        onUi(m::analyze);
        assertEquals(Analysis.RUNNING, onUi(m::analysis));
        access.revoke();
        release.countDown();
        await(() -> m.analysis() == Analysis.IDLE);
        assertEquals(List.of(), onUi(m::weakRows));
    }

    @Test
    @DisplayName("分析途中還原備份（換了一次解鎖）時用新的內容重算")
    void restoreDuringAnalysisRecomputes() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        access.open().publish(data(bank));
        HealthReportModel m = model(blockedUntil(release));
        onUi(m::analyze);
        access.open().publish(data(mail, shop)); // 還原：舊的租約失效、新的開啟
        release.countDown();
        await(() -> m.analysis() == Analysis.READY);
        assertEquals(List.of(), onUi(m::weakRows));
        assertEquals(List.of("Mail", "Shop"), titles(onUi(m::reusedGroups).get(0)));
    }

    @Test
    @DisplayName("外洩檢查途中上鎖時不顯示結果、回到「尚未檢查」、不記時間")
    void lockDuringBreachDropsResult() throws Exception {
        hibp.answer(WEAK, 7);
        hibp.delay(300);
        HealthReportModel m = unlockedModel();
        onUi(m::checkBreach);
        await(() -> !hibp.paths().isEmpty());
        access.revoke();
        await(() -> m.breach() != Breach.RUNNING);
        assertEquals(Breach.NOT_CHECKED, onUi(m::breach));
        assertEquals(List.of(), onUi(m::breachRows));
        assertFalse(onUi(() -> prefs.number(HealthReportModel.KEY_LAST_BREACH_CHECK)).isPresent());
    }

    @Test
    @DisplayName("關頁（desktop 離開頁面、android 的 ViewModel 清除）時中斷外洩檢查，之後不再通知、不再開始")
    void closeCancelsBreach() throws Exception {
        hibp.delay(1_500);
        HealthReportModel m = unlockedModel();
        onUi(m::checkBreach);
        await(() -> !hibp.paths().isEmpty());
        onUi(() -> {
            m.close();
            return null;
        });
        int before = events.size();
        background.shutdown();
        assertTrue(background.awaitTermination(5, TimeUnit.SECONDS), "背景檢查被中斷後結束");
        onUi(() -> null); // 讓排進 UI 執行緒的回呼都跑完
        assertEquals(before, events.size(), "關頁後不再通知：" + events);
        // 有 2 個前綴：中斷後在第二個前綴之前停下，只送出一個請求
        assertEquals(1, hibp.paths().size(), "中斷後不再送出：" + hibp.paths());
        assertFalse(onUi(m::analyze));
        assertFalse(onUi(m::checkBreach));
        assertEquals(List.of(), onUi(m::breachRows));
    }

    @Test
    @DisplayName("畫面重建時拿掉監聽（null）→ 檢查照常完成；新畫面接上監聽後讀得到結果")
    void detachedListenerKeepsRunning() throws Exception {
        hibp.answer(WEAK, 5);
        HealthReportModel m = unlockedModel();
        onUi(m::analyze);
        await(() -> m.analysis() == Analysis.READY);
        onUi(() -> {
            m.checkBreach();
            m.setListener(null); // android 旋轉：舊畫面 onDestroyView
            return null;
        });
        int before = events.size();
        await(() -> m.breach() == Breach.COMPLETED);
        assertEquals(before, events.size(), "沒有監聽時不通知：" + events);
        assertEquals(List.of("Bank"), titles(onUi(m::breachRows)));
    }

    @Test
    @DisplayName("日誌不記條目名稱、密碼與雜湊前綴；列的 toString 只有 UUID")
    void logsHaveNoSecrets() throws Exception {
        hibp.answer(WEAK, 42);
        HealthReportModel m = unlockedModel();
        onUi(m::analyze);
        await(() -> m.analysis() == Analysis.READY);
        onUi(m::checkBreach);
        await(() -> m.breach() == Breach.COMPLETED);

        assertFalse(logs.isEmpty(), "應該有完成的日誌");
        String prefixA = FakeHibp.sha1(WEAK).substring(0, 5);
        String prefixB = FakeHibp.sha1(SHARED).substring(0, 5);
        for (String line : logs) {
            for (String secret : List.of("Bank", "Mail", "Shop", WEAK, SHARED, prefixA, prefixB)) {
                assertFalse(line.contains(secret), "日誌不能有 " + secret + "：" + line);
            }
        }
        Row row = onUi(m::breachRows).get(0);
        assertFalse(row.toString().contains("Bank"));
        assertTrue(row.toString().contains(bank.uuid().toString()));
    }
}
