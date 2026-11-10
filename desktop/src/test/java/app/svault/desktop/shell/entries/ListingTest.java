package app.svault.desktop.shell.entries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 條目清單列出什麼：群組、資源回收桶、搜尋與四種空狀態。
 */
class ListingTest {

    private static final Instant T = Instant.parse("2026-10-18T00:00:00Z");
    private final UUID root = UUID.randomUUID();
    private final UUID work = UUID.randomUUID();
    private final UUID bin = UUID.randomUUID();
    private final List<GroupView> groups = List.of(
            new GroupView(root, null, "我的密碼", "", false, T),
            new GroupView(work, root, "工作", "", false, T),
            new GroupView(bin, root, "資源回收桶", "", true, T));

    @Test
    @DisplayName("AC-03-1：名稱、帳號或網址包含關鍵字（不分大小寫）就列出，不搜尋密碼；搜尋全部群組、不含回收桶")
    void searchFieldsAndScope() {
        ScreenData data = ScreenData.of(List.of(
                entry("GitHub", "rory", "zzz", "", work),
                entry("Mail", "GITHUB-fan", "", "", root),
                entry("Bank", "x", "", "https://github.example", root),
                entry("Secret", "y", "github", "", root),
                entry("Old GitHub", "", "", "", bin)), groups);

        Listing found = Listing.of(data, Listing.Scope.group(root), "  gitHUB ");

        assertTrue(found.search());
        assertEquals(List.of("Bank", "GitHub", "Mail"), titles(found));
    }

    @Test
    @DisplayName("AC-03-3：沒有符合的條目時是「找不到」")
    void noResults() {
        ScreenData data = ScreenData.of(List.of(entry("A", "", "", "", root)), groups);
        assertEquals(Listing.Empty.NO_RESULTS, Listing.of(data, Listing.Scope.group(root), "zzz").empty());
        assertEquals("找不到符合的條目",
                java.util.ResourceBundle.getBundle("app.svault.desktop.shell.entries.messages")
                        .getString("list.empty.search.title"));
    }

    @Test
    @DisplayName("AC-02-6：整個保管庫沒有條目時是「建立你的第一筆條目」；只有這個群組空時是群組的空狀態")
    void emptyStates() {
        ScreenData empty = ScreenData.of(List.of(), groups);
        assertEquals(Listing.Empty.VAULT, Listing.of(empty, Listing.Scope.group(root), "").empty());
        assertEquals(Listing.Empty.RECYCLE_BIN, Listing.of(empty, Listing.Scope.bin(bin), "").empty());

        ScreenData some = ScreenData.of(List.of(entry("A", "", "", "", root)), groups);
        assertEquals(Listing.Empty.GROUP, Listing.of(some, Listing.Scope.group(work), "").empty());
        assertEquals(Listing.Empty.NONE, Listing.of(some, Listing.Scope.group(root), "").empty());
        assertFalse(Listing.of(some, Listing.Scope.group(root), "").search());
    }

    @Test
    @DisplayName("資源回收桶列出裡面所有條目（含被刪除的群組裡的）；還沒有回收桶時是空的")
    void recycleBin() {
        UUID deletedGroup = UUID.randomUUID();
        List<GroupView> withDeleted = new ArrayList<>(groups);
        withDeleted.add(new GroupView(deletedGroup, bin, "舊群組", "", false, T));
        ScreenData data = ScreenData.of(List.of(entry("X", "", "", "", bin), entry("Y", "", "", "", deletedGroup)),
                withDeleted);

        assertEquals(List.of("X", "Y"), titles(Listing.of(data, Listing.Scope.bin(bin), "")));
        assertTrue(data.inRecycleBin(deletedGroup));
        assertEquals(List.of("Y"), titles(Listing.of(data, Listing.Scope.group(deletedGroup), "")));
        assertEquals(List.of(root, work), data.activeGroups().stream().map(GroupView::uuid).toList());

        ScreenData noBin = ScreenData.of(List.of(), groups.subList(0, 2));
        assertTrue(noBin.recycleBin().isEmpty());
        assertEquals(Listing.Empty.RECYCLE_BIN, Listing.of(noBin, Listing.Scope.bin(null), "").empty());
    }

    @Test
    @DisplayName("AC-03-2：2,000 筆時每輸入一個字元，重新算出清單在 300 毫秒內")
    void searchTwoThousandIsFast() {
        List<EntryView> many = new ArrayList<>();
        for (int i = 0; i < 2000; i++) {
            many.add(entry("網站 " + i + " example", "user" + i + "@mail.test", "pw" + i,
                    "https://site" + i + ".example.com/login", i % 2 == 0 ? root : work));
        }
        ScreenData data = ScreenData.of(many, groups);
        String typed = "site19.example";
        long worst = 0;
        for (int n = 1; n <= typed.length(); n++) {
            long start = System.nanoTime();
            Listing l = Listing.of(data, Listing.Scope.group(root), typed.substring(0, n));
            l.entries().size();
            worst = Math.max(worst, System.nanoTime() - start);
        }
        assertTrue(worst < 300_000_000L, "最慢一次 " + worst / 1_000_000 + " ms");
    }

    private EntryView entry(String title, String user, String password, String url, UUID group) {
        return new EntryView(UUID.randomUUID(), group, title, user, password, url, "", Map.of(), List.of(), "", T, T,
                0);
    }

    private static List<String> titles(Listing listing) {
        return listing.entries().stream().map(EntryView::title).toList();
    }
}
