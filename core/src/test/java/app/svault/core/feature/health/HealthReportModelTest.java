package app.svault.core.feature.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.feature.health.HealthReportModel.Analysis;
import app.svault.core.feature.health.HealthReportModel.Breach;
import app.svault.core.feature.health.HealthReportModel.Reason;
import app.svault.core.feature.health.HealthReportModel.Row;
import app.svault.core.vault.EntryView;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 健檢報告頁的狀態（T-52、T-53；T-117 合進 core）：弱／重複（AC-13-1）、外洩檢查（AC-13-2～AC-13-4；本機假伺服器，
 * 不連真正的 HIBP）。上鎖、還原、關頁、重建與日誌在 {@link HealthReportLifecycleTest}。
 */
class HealthReportModelTest extends HealthModelFixture {

    /** AC-13-1：列出弱密碼與重複使用的密碼（一組一個清單，原因帶共用的筆數），列帶條目 UUID 供跳到條目。 */
    @Test
    public void listsWeakAndReused() throws Exception {
        HealthReportModel m = unlockedModel();
        assertTrue(onUi(m::analyze));
        await(() -> m.analysis() == Analysis.READY);

        List<Row> weak = onUi(m::weakRows);
        assertEquals(List.of("Bank"), titles(weak));
        assertEquals(bank.uuid(), weak.get(0).entryId());
        assertEquals(Reason.WEAK, weak.get(0).reason());

        List<List<Row>> groups = onUi(m::reusedGroups);
        assertEquals(1, groups.size());
        assertEquals(List.of("Mail", "Shop"), titles(groups.get(0)));
        assertEquals(List.of(mail.uuid(), shop.uuid()), groups.get(0).stream().map(Row::entryId).toList());
        assertEquals(Reason.REUSED, groups.get(0).get(0).reason());
        assertEquals(1, groups.get(0).get(0).count());
        assertEquals(2, (int) onUi(m::reusedCount));
        assertFalse(onUi(m::isClean));
        // 弱／重複的分析不連網（AC-13-5 的頁面部分）
        assertEquals(List.of(), hibp.paths());
        assertEquals(Breach.NOT_CHECKED, onUi(m::breach));
    }

    /** AC-13-1：沒有弱也沒有重複時是「乾淨」；名稱空白的條目名稱是空字串（畫面顯示「（沒有名稱）」）。 */
    @Test
    public void cleanAndUntitled() throws Exception {
        access.open().publish(data(entry(WEB, "", "correct-horse-battery-staple-9431")));
        HealthReportModel m = model(() -> analyzer);
        onUi(m::analyze);
        await(() -> m.analysis() == Analysis.READY);
        assertTrue(onUi(m::isClean));

        access.revoke();
        EntryView untitled = entry(WEB, "", WEAK);
        access.open().publish(data(untitled));
        HealthReportModel m2 = model(() -> analyzer);
        onUi(m2::analyze);
        await(() -> m2.analysis() == Analysis.READY);
        assertEquals(List.of(""), titles(onUi(m2::weakRows)));
        assertEquals(untitled.uuid(), onUi(m2::weakRows).get(0).entryId());
    }

    /** AC-13-2：按下並確認後才連線；送出的只有 /range/ 加 SHA-1 前 5 碼，同一前綴只查一次；進度記在模型裡。 */
    @Test
    public void breachSendsOnlyPrefixes() throws Exception {
        HealthReportModel m = unlockedModel();
        onUi(m::analyze);
        await(() -> m.analysis() == Analysis.READY);
        assertEquals(List.of(), hibp.paths());

        assertTrue(onUi(m::checkBreach));
        await(() -> m.breach() == Breach.COMPLETED);

        List<String> paths = hibp.paths();
        // Bank（password）、Mail／Shop（同一密碼）→ 2 個前綴；沒有密碼與回收桶的不送
        assertEquals(List.of("/range/" + FakeHibp.sha1(WEAK).substring(0, 5),
                "/range/" + FakeHibp.sha1(SHARED).substring(0, 5)).stream().sorted().toList(),
                paths.stream().sorted().toList());
        for (String p : paths) {
            assertTrue(p.matches("/range/[0-9A-F]{5}"), "只送前 5 碼：" + p);
            assertFalse(p.contains(FakeHibp.sha1(WEAK).substring(5)));
            assertFalse(p.contains(FakeHibp.sha1(SHARED).substring(5)));
        }
        assertTrue(events.contains("progress:0/2"), events.toString());
        assertTrue(events.contains("progress:2/2"), events.toString());
        assertEquals(2, (int) onUi(m::breachDone));
        assertEquals(2, (int) onUi(m::breachTotal));
    }

    /** AC-13-3：完成後列出出現在外洩資料中的條目與次數，並記下檢查時間（不記結果）。 */
    @Test
    public void breachListsHits() throws Exception {
        hibp.answer(WEAK, 3_861_493);
        HealthReportModel m = unlockedModel();
        onUi(m::analyze);
        await(() -> m.analysis() == Analysis.READY);
        onUi(m::checkBreach);
        await(() -> m.breach() == Breach.COMPLETED);

        List<Row> hits = onUi(m::breachRows);
        assertEquals(List.of("Bank"), titles(hits));
        assertEquals(bank.uuid(), hits.get(0).entryId());
        assertEquals(Reason.BREACHED, hits.get(0).reason());
        assertEquals(3_861_493, hits.get(0).count());
        assertEquals(Optional.of(CLOCK.millis()),
                onUi(() -> prefs.number(HealthReportModel.KEY_LAST_BREACH_CHECK)));
    }

    /** AC-13-3：沒有命中時完成、清單為空。 */
    @Test
    public void breachClean() throws Exception {
        HealthReportModel m = unlockedModel();
        onUi(m::checkBreach);
        await(() -> m.breach() == Breach.COMPLETED);
        assertEquals(List.of(), onUi(m::breachRows));
    }

    /** AC-13-4：連線失敗時狀態是 CONNECTION_FAILED，弱／重複照常，不記檢查時間；再按一次可以重試。 */
    @Test
    public void breachFailureKeepsReport() throws Exception {
        hibp.failWith(503);
        HealthReportModel m = unlockedModel();
        onUi(m::analyze);
        await(() -> m.analysis() == Analysis.READY);
        onUi(m::checkBreach);
        await(() -> m.breach() == Breach.CONNECTION_FAILED);

        assertEquals(Analysis.READY, onUi(m::analysis));
        assertEquals(List.of("Bank"), titles(onUi(m::weakRows)));
        assertEquals(1, onUi(m::reusedGroups).size());
        assertEquals(List.of(), onUi(m::breachRows));
        assertEquals(Optional.empty(), onUi(() -> prefs.number(HealthReportModel.KEY_LAST_BREACH_CHECK)));

        hibp.failWith(200);
        assertTrue(onUi(m::checkBreach));
        await(() -> m.breach() == Breach.COMPLETED);
    }

    /** AC-13-4：連不上（沒有伺服器在聽，例如飛航模式）也是 CONNECTION_FAILED。 */
    @Test
    public void breachNoServer() throws Exception {
        String base = hibp.baseUrl();
        hibp.close();
        access.open().publish(data(bank));
        HealthReportModel m = model(() -> analyzer, base);
        onUi(m::checkBreach);
        await(() -> m.breach() == Breach.CONNECTION_FAILED);
    }
}
