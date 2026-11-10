package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MutableClock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EntryQuery}：關鍵字搜尋（AC-03-1）、2,000 筆的效能（AC-03-2）、群組與回收桶的查詢。
 */
class EntryQueryTest {

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"), ZoneOffset.UTC);
    private Vault vault;
    private VaultEditor editor;

    @BeforeEach
    void open() {
        vault = codec.create("查詢測試", TestVaults.password());
        editor = new VaultEditor(vault, clock);
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    @DisplayName("AC-03-1：名稱、帳號或網址包含關鍵字（不分大小寫）就列出；不搜尋密碼與備註")
    void searchMatchesTitleUsernameUrl() {
        UUID byTitle = editor.addEntry(vault.id(), new EntryDraft("GitHub", "rory", "x", "", ""));
        UUID byUser = editor.addEntry(vault.id(), new EntryDraft("工作信箱", "Rory.Chen@Example.com", "x", "", ""));
        UUID byUrl = editor.addEntry(vault.id(), new EntryDraft("銀行", "u", "x", "https://BANK.example/login", ""));
        editor.addEntry(vault.id(), new EntryDraft("只有密碼有", "u", "example-pass", "", ""));
        editor.addEntry(vault.id(), new EntryDraft("只有備註有", "u", "x", "", "example 便條紙"));

        EntryQuery query = EntryQuery.of(vault);

        assertEquals(List.of(byTitle), ids(query.search("github")));
        assertEquals(List.of(byTitle), ids(query.search("GITHUB")));
        assertEquals(List.of(byUrl, byUser).stream().sorted().collect(Collectors.toList()),
                ids(query.search("EXAMPLE")).stream().sorted().collect(Collectors.toList()));
        assertEquals(List.of(byUrl), ids(query.search("bank.EXAMPLE")));
        assertEquals(List.of(byUser), ids(query.search("  rory.chen ")), "前後空白不算");
        assertEquals(List.of(byUrl), ids(query.search("銀")));
        assertTrue(query.search("example-pass").isEmpty(), "不搜尋密碼");
        assertTrue(query.search("便條紙").isEmpty(), "不搜尋備註");
        assertTrue(query.search("沒有這個").isEmpty(), "AC-03-3 的「找不到」由畫面依空清單顯示");
        assertEquals(5, query.search("").size(), "空關鍵字＝全部");
    }

    @Test
    @DisplayName("AC-03-1：資源回收桶裡的條目不出現在搜尋與全部條目，另由 recycled() 列出")
    void recycleBinIsExcluded() {
        UUID kept = editor.addEntry(vault.id(), new EntryDraft("Example 保留", "", "", "", ""));
        UUID gone = editor.addEntry(vault.id(), new EntryDraft("Example 刪掉", "", "", "", ""));
        editor.recycleEntry(gone);

        EntryQuery query = EntryQuery.of(vault);

        assertEquals(List.of(kept), ids(query.search("example")));
        assertEquals(List.of(kept), ids(query.all()));
        assertEquals(List.of(gone), ids(query.recycled()));
    }

    @Test
    @DisplayName("群組：直接所屬的條目、含子群組的條目數（AC-02-4 刪除前提示）、群組路徑（搜尋結果的第二行）")
    void groupQueries() {
        UUID a = editor.addGroup(vault.id(), "工作");
        UUID b = editor.addGroup(a, "專案");
        UUID inA = editor.addEntry(a, new EntryDraft("甲", "", "", "", ""));
        editor.addEntry(b, new EntryDraft("乙", "", "", "", ""));
        editor.addEntry(b, new EntryDraft("丙", "", "", "", ""));

        EntryQuery query = EntryQuery.of(vault);

        assertEquals(List.of(inA), ids(query.inGroup(a)));
        assertEquals(3, query.countInGroup(a));
        assertEquals(2, query.countInGroup(b));
        assertEquals(3, query.countInGroup(vault.id()));
        assertEquals("查詢測試 / 工作 / 專案", query.groupPath(b));
        assertEquals("查詢測試", query.groupPath(vault.id()));
    }

    @Test
    @DisplayName("結果依名稱排序（繁體中文 Collator；英文不分大小寫）")
    void resultsAreSortedByTitle() {
        for (String title : List.of("zoom", "Apple", "銀行", "apple pay", "Bank")) {
            editor.addEntry(vault.id(), new EntryDraft(title, "", "", "", ""));
        }
        List<String> titles = EntryQuery.of(vault).all().stream().map(EntryView::title).collect(Collectors.toList());
        assertEquals(List.of("Apple", "apple pay", "Bank", "zoom", "銀行"), titles);
    }

    @Test
    @DisplayName("AC-03-2：2,000 筆條目時，每輸入一個字元後 300 毫秒內更新結果")
    void searchTwoThousandEntriesWithinBudget() {
        for (int i = 0; i < 2000; i++) {
            editor.addEntry(vault.id(), new EntryDraft("網站 " + i + " Service", "user" + i + "@mail.example",
                    "pw-" + i, "https://site" + i + ".example.com/login", "備註 " + i));
        }
        String typed = "site1999.example";
        // 第一輪只暖機（Collator 初始化、JIT），不計；之後量 5 輪取中位數。CI 的共用機器只量一次會超標（M-01 整合的 CI 第 1 次）
        searchRound(typed);
        List<Long> buildMs = new ArrayList<>();
        List<Long> maxMicros = new ArrayList<>();
        for (int round = 0; round < 5; round++) {
            long[] r = searchRound(typed);
            buildMs.add(r[0]);
            maxMicros.add(r[1]);
        }
        long buildMedian = median(buildMs);
        long maxMedian = median(maxMicros);
        // 不含帳密內容，只有假資料的計時；收尾時寫進 metrics.csv
        System.out.println("AC-03-2：建立索引 " + buildMs + " ms（中位數 " + buildMedian + "）；每個字元最長 " + maxMicros
                + " µs（中位數 " + maxMedian + "）");

        assertTrue(maxMedian < 300_000, "每個字元的搜尋要在 300 ms 內：各輪最長 " + maxMicros + " µs");
        assertTrue(buildMedian < 300, "建立索引（解鎖或存檔後做一次）也在 300 ms 內：各輪 " + buildMs + " ms");
    }

    /** 建立索引並逐字輸入一次；回傳 {建立索引 ms, 每個字元中最長的 µs}。 */
    private long[] searchRound(String typed) {
        long buildStart = System.nanoTime();
        EntryQuery query = EntryQuery.of(vault);
        long buildMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - buildStart);
        long maxMicros = 0;
        int lastSize = -1;
        for (int i = 1; i <= typed.length(); i++) {
            long start = System.nanoTime();
            lastSize = query.search(typed.substring(0, i)).size();
            maxMicros = Math.max(maxMicros, TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start));
        }
        assertEquals(1, lastSize);
        return new long[] {buildMs, maxMicros};
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(sorted.size() / 2);
    }

    private static List<UUID> ids(List<EntryView> entries) {
        return entries.stream().map(EntryView::uuid).collect(Collectors.toList());
    }
}
