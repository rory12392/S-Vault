package app.svault.android.shell.entries;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * 條目清單列出什麼（「全部條目」、群組、資源回收桶、搜尋與四種空狀態）。
 */
public class ListingTest {

    private static final Instant T = Instant.parse("2026-10-19T00:00:00Z");
    private final UUID root = UUID.randomUUID();
    private final UUID work = UUID.randomUUID();
    private final UUID bin = UUID.randomUUID();
    private final List<GroupView> groups = List.of(
            new GroupView(root, null, "我的密碼", "", false, T),
            new GroupView(work, root, "工作", "", false, T),
            new GroupView(bin, root, "資源回收桶", "", true, T));

    @Test
    public void ac03_1_searchNameUsernameUrlIgnoringCaseNotPasswordAllGroupsNotBin() {
        ScreenData data = ScreenData.of(List.of(
                entry("GitHub", "rory", "zzz", "", work),
                entry("Mail", "GITHUB-fan", "", "", root),
                entry("Bank", "x", "", "https://github.example", root),
                entry("Secret", "y", "github", "", root),
                entry("Old GitHub", "", "", "", bin)), groups);

        Listing found = Listing.of(data, Listing.Scope.group(work), "  gitHUB ");

        assertTrue(found.search());
        assertEquals(List.of("Bank", "GitHub", "Mail"), titles(found));
    }

    @Test
    public void ac03_3_noResults() {
        ScreenData data = ScreenData.of(List.of(entry("A", "", "", "", root)), groups);
        assertEquals(Listing.Empty.NO_RESULTS, Listing.of(data, Listing.Scope.all(), "zzz").empty());
    }

    @Test
    public void ac02_6_emptyVaultAndEmptyGroup() {
        ScreenData empty = ScreenData.of(List.of(), groups);
        assertEquals(Listing.Empty.VAULT, Listing.of(empty, Listing.Scope.all(), "").empty());
        assertEquals(Listing.Empty.VAULT, Listing.of(empty, Listing.Scope.group(work), "").empty());
        assertEquals(Listing.Empty.RECYCLE_BIN, Listing.of(empty, Listing.Scope.bin(), "").empty());

        ScreenData some = ScreenData.of(List.of(entry("A", "", "", "", root)), groups);
        assertEquals(Listing.Empty.GROUP, Listing.of(some, Listing.Scope.group(work), "").empty());
        assertEquals(Listing.Empty.NONE, Listing.of(some, Listing.Scope.all(), "").empty());
        assertFalse(Listing.of(some, Listing.Scope.all(), "").search());
    }

    @Test
    public void allEntriesListsEveryGroupButNotBin() {
        ScreenData data = ScreenData.of(List.of(entry("B", "", "", "", work), entry("A", "", "", "", root),
                entry("X", "", "", "", bin)), groups);

        assertEquals(List.of("A", "B"), titles(Listing.of(data, Listing.Scope.all(), "")));
        assertEquals(List.of("B"), titles(Listing.of(data, Listing.Scope.group(work), "")));
    }

    @Test
    public void recycleBinListsEverythingInsideAndMissingBinIsEmpty() {
        UUID deletedGroup = UUID.randomUUID();
        List<GroupView> withDeleted = new ArrayList<>(groups);
        withDeleted.add(new GroupView(deletedGroup, bin, "舊群組", "", false, T));
        ScreenData data = ScreenData.of(List.of(entry("X", "", "", "", bin), entry("Y", "", "", "", deletedGroup)),
                withDeleted);

        assertEquals(List.of("X", "Y"), titles(Listing.of(data, Listing.Scope.bin(), "")));
        assertTrue(data.inRecycleBin(deletedGroup));
        assertEquals(Listing.Empty.NONE, Listing.of(data, Listing.Scope.group(deletedGroup), "").empty());
        assertEquals(List.of(root, work),
                data.activeGroups().stream().map(GroupView::uuid).collect(Collectors.toList()));

        ScreenData noBin = ScreenData.of(List.of(), groups.subList(0, 2));
        assertFalse(noBin.recycleBin().isPresent());
        assertEquals(Listing.Empty.RECYCLE_BIN, Listing.of(noBin, Listing.Scope.bin(), "").empty());
    }

    @Test
    public void ac03_2_twoThousandEntriesEachCharacterUnder300ms() {
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
            Listing l = Listing.of(data, Listing.Scope.all(), typed.substring(0, n));
            l.entries().size();
            worst = Math.max(worst, System.nanoTime() - start);
        }
        assertTrue("最慢一次 " + worst / 1_000_000 + " ms", worst < 300_000_000L);
    }

    private EntryView entry(String title, String user, String password, String url, UUID group) {
        return new EntryView(UUID.randomUUID(), group, title, user, password, url, "", Map.of(), List.of(), "", T, T,
                0);
    }

    private static List<String> titles(Listing listing) {
        return listing.entries().stream().map(EntryView::title).collect(Collectors.toList());
    }
}
