package app.svault.core.feature.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.security.StrengthEstimator;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryQuery;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WeakReuseAnalyzer}：健檢報告的弱密碼與重複密碼（AC-13-1 的報告邏輯；畫面與跳到條目在 T-52／T-53）。
 */
class WeakReuseAnalyzerTest {

    /** zxcvbn 評為強的密碼（{@code StrengthEstimatorTest} 用過）；加上不同字尾讓每筆都不重複。 */
    private static final String STRONG = "g7#Kq!2vZp@9LmW$xR4t";

    private static final StrengthEstimator ESTIMATOR = new StrengthEstimator();

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC);
    private final WeakReuseAnalyzer analyzer = new WeakReuseAnalyzer(ESTIMATOR);
    private Vault vault;
    private VaultEditor editor;

    @BeforeEach
    void open() {
        vault = codec.create("健檢測試", TestVaults.password());
        editor = new VaultEditor(vault, clock);
    }

    @AfterEach
    void close() {
        vault.close();
    }

    private UUID add(String title, String username, String password) {
        return editor.addEntry(vault.id(), new EntryDraft(title, username, password, "", ""));
    }

    private WeakReuseReport analyze() {
        return analyzer.analyze(EntryQuery.of(vault));
    }

    private static List<UUID> ids(List<EntryView> entries) {
        return entries.stream().map(EntryView::uuid).collect(Collectors.toList());
    }

    @Test
    @DisplayName("AC-13-1：強度評為弱的密碼列在弱密碼清單，中、強的不列")
    void weakPasswordsAreListed() {
        UUID common = add("B 論壇", "u1", "password");
        UUID shortOne = add("A 舊信箱", "u2", "abc");
        add("C 銀行", "u3", STRONG);
        add("D 中等", "u4", "Kq7!vZp2x");

        WeakReuseReport report = analyze();

        assertEquals(List.of(shortOne, common), ids(report.weak()), "依名稱排序（EntryQuery 的順序）");
        assertTrue(report.reused().isEmpty());
        assertFalse(report.isEmpty());
    }

    @Test
    @DisplayName("AC-13-1：條目的名稱與帳號當強度評估的上下文，密碼和它們相近時評為弱")
    void titleAndUsernameAreContext() {
        // 單獨評估不是弱（StrengthEstimatorTest 的 contextLowersStrength），和帳號相同時降為弱
        UUID sameAsUser = add("某服務", "rorysvaultzq", "rorysvaultzq");
        add("別的服務", "someone", STRONG + "a");

        assertEquals(List.of(sameAsUser), ids(analyze().weak()));
    }

    @Test
    @DisplayName("AC-13-1：完全相同的密碼出現在 2 筆以上時列為一組，顯示共用同一密碼的條目")
    void reusedPasswordsAreGrouped() {
        UUID a1 = add("A 商店", "u", STRONG + "shared1");
        UUID a2 = add("B 商店", "u", STRONG + "shared1");
        UUID a3 = add("C 商店", "u", STRONG + "shared1");
        UUID b1 = add("D 論壇", "u", STRONG + "shared2");
        UUID b2 = add("E 論壇", "u", STRONG + "shared2");
        add("F 獨立", "u", STRONG + "unique");
        // 只差大小寫不是相同的密碼
        add("G 大寫", "u", STRONG + "SHARED1");

        WeakReuseReport report = analyze();

        assertEquals(2, report.reused().size());
        assertEquals(List.of(a1, a2, a3), ids(report.reused().get(0).entries()));
        assertEquals(List.of(b1, b2), ids(report.reused().get(1).entries()));
        assertEquals(2, report.reused().get(0).othersCount(a1), "「與 2 筆條目使用相同密碼」");
        assertEquals(5, report.reusedEntryCount());
        assertTrue(report.weak().isEmpty());
    }

    @Test
    @DisplayName("AC-13-1：弱而且重複的密碼兩個清單都列")
    void weakAndReusedAppearInBoth() {
        UUID x = add("A 甲", "u1", "123456");
        UUID y = add("B 乙", "u2", "123456");

        WeakReuseReport report = analyze();

        assertEquals(List.of(x, y), ids(report.weak()));
        assertEquals(1, report.reused().size());
        assertEquals(List.of(x, y), ids(report.reused().get(0).entries()));
    }

    @Test
    @DisplayName("AC-13-1：資源回收桶裡的條目不列入弱密碼，也不和其他條目算成重複")
    void recycleBinIsExcluded() {
        add("留著的", "u", STRONG + "dup");
        UUID binned = add("丟掉的", "u", STRONG + "dup");
        UUID weakBinned = add("丟掉的弱密碼", "u", "password");
        editor.recycleEntry(binned);
        editor.recycleEntry(weakBinned);

        assertTrue(analyze().isEmpty());
    }

    @Test
    @DisplayName("AC-13-1：沒有密碼的條目（例如只放備註）不列為弱密碼，也不算重複")
    void emptyPasswordsAreSkipped() {
        add("只有備註一", "", "");
        add("只有備註二", "", "");

        WeakReuseReport report = analyze();

        assertTrue(report.isEmpty());
        assertEquals(0, report.reusedEntryCount());
    }

    @Test
    @DisplayName("AC-13-1：沒有問題時報告是空的（畫面顯示「沒有發現弱密碼或重複使用的密碼」）")
    void cleanVaultIsEmpty() {
        assertTrue(analyze().isEmpty());
        add("唯一", "u", STRONG);
        assertTrue(analyze().isEmpty());
    }

    @Test
    @DisplayName("報告的 toString 只有 UUID 與數量，不含密碼、名稱、帳號")
    void toStringDoesNotLeak() {
        add("秘密網站", "secret-user", "hunter2");
        add("秘密網站二", "secret-user", "hunter2");

        WeakReuseReport report = analyze();

        for (String text : List.of(report.toString(), report.reused().get(0).toString())) {
            assertFalse(text.contains("hunter2"), text);
            assertFalse(text.contains("秘密網站"), text);
            assertFalse(text.contains("secret-user"), text);
        }
    }

    @Test
    @DisplayName("othersCount：不在這一組的條目丟出例外")
    void othersCountRequiresMember() {
        add("一", "u", STRONG + "x");
        add("二", "u", STRONG + "x");
        ReuseGroup group = analyze().reused().get(0);

        assertThrows(IllegalArgumentException.class,
                () -> group.othersCount(UUID.randomUUID()));
    }

    @Test
    @DisplayName("2,000 筆條目的分析時間（只記錄，畫面在背景執行並顯示 ProgressOverlay）")
    void twoThousandEntries() {
        List<UUID> added = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            // 每 10 筆共用一個密碼、每 100 筆有一個弱密碼，模擬一般使用者的保管庫
            String password = i % 100 == 0 ? "password" + i : STRONG + (i / 10);
            added.add(add("網站 " + i, "user" + i + "@mail.example", password));
        }
        EntryQuery query = EntryQuery.of(vault);
        long start = System.nanoTime();
        WeakReuseReport report = analyzer.analyze(query);
        long ms = (System.nanoTime() - start) / 1_000_000;
        // 只有假資料的計時；寫進 dev-log
        System.out.println("T-50：2,000 筆健檢分析 " + ms + " ms");

        assertEquals(20, report.weak().size());
        assertEquals(added.size(), query.all().size());
        assertEquals(200, report.reused().size());
    }
}
