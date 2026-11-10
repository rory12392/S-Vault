package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EntryQuery#autofill}：自動填入的候選條目（AC-10-3、AC-10-5）。比對規則本身見 {@link UrlMatchTest}。
 */
class EntryQueryAutofillTest {

    private final UUID root = UUID.randomUUID();
    private final UUID bin = UUID.randomUUID();
    private final List<GroupView> groups = List.of(
            new GroupView(root, null, "根", "", false, Instant.EPOCH),
            new GroupView(bin, root, "資源回收桶", "", true, Instant.EPOCH));
    private final List<EntryView> entries = new ArrayList<>();

    @Test
    @DisplayName("AC-10-3：網頁請求只列出網域符合的條目，完全相同的在前、上層網域在後，各自依名稱排序")
    void webRequestListsMatchingEntries() {
        EntryView parentB = add("B GitHub 主站", "https://github.com/");
        EntryView exact = add("Gist", "https://gist.github.com/");
        EntryView parentA = add("A GitHub 備用", "github.com");
        add("GitLab", "https://gitlab.com/");
        add("Discord App", "androidapp://com.discord");
        add("沒有網址", "");

        List<EntryView> found = query().autofill("com.android.chrome", "gist.github.com");

        assertEquals(ids(List.of(exact, parentA, parentB)), ids(found));
    }

    @Test
    @DisplayName("AC-10-3：App 請求只列出 androidapp://<套件名稱> 的條目")
    void appRequestListsPackageEntries() {
        EntryView app = add("Discord", "androidapp://com.discord");
        add("Discord 網頁", "https://discord.com/");
        add("別的 App", "androidapp://com.instagram.android");

        assertEquals(ids(List.of(app)), ids(query().autofill("com.discord", null)));
    }

    @Test
    @DisplayName("AC-10-5：資源回收桶裡的條目不提供；沒有符合時回傳空清單（模組改提供「搜尋保管庫」）")
    void recycledAndNoMatch() {
        add("已刪除", "https://github.com/", bin);

        assertTrue(query().autofill("com.android.chrome", "github.com").isEmpty());
        assertTrue(query().autofill("com.android.chrome", "").isEmpty(), "瀏覽器沒有 webDomain 時由模組判斷，這裡只比套件");
        assertTrue(query().autofill(null, null).isEmpty());
    }

    @Test
    @DisplayName("AC-10-3：最多 10 筆，完全相同的優先保留")
    void limitedToTen() {
        for (int i = 0; i < 12; i++) {
            add(String.format("上層 %02d", i), "https://example.com/");
        }
        EntryView exact = add("最後才加的完全相同", "https://login.example.com/");

        List<EntryView> found = query().autofill("com.android.chrome", "login.example.com");

        assertEquals(EntryQuery.AUTOFILL_LIMIT, found.size());
        assertEquals(10, EntryQuery.AUTOFILL_LIMIT);
        assertEquals(exact.uuid(), found.get(0).uuid());
        assertEquals("上層 08", found.get(9).title());
    }

    private EntryQuery query() {
        return new EntryQuery(entries, groups);
    }

    private EntryView add(String title, String url) {
        return add(title, url, root);
    }

    private EntryView add(String title, String url, UUID group) {
        EntryView e = new EntryView(UUID.randomUUID(), group, title, "u", "p", url, "", Map.of(), List.of(), "",
                Instant.EPOCH, Instant.EPOCH, 0);
        entries.add(e);
        return e;
    }

    private static List<UUID> ids(List<EntryView> list) {
        return list.stream().map(EntryView::uuid).collect(Collectors.toList());
    }
}
