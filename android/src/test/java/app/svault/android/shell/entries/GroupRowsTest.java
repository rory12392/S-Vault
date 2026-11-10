package app.svault.android.shell.entries;

import static org.junit.Assert.assertEquals;

import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * 首頁的群組清單：「全部條目」→ 各群組（依檔案順序、縮排表示層級）→
 * 「資源回收桶」固定在最後，裡面被刪除的群組也列出（可以還原）。
 */
public class GroupRowsTest {

    private static final Instant T = Instant.parse("2026-10-19T00:00:00Z");

    @Test
    public void allThenGroupsByDepthThenBinWithRecycledGroups() {
        UUID root = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID bin = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID home = UUID.randomUUID();
        ScreenData data = ScreenData.of(List.of(entry(root), entry(work), entry(project), entry(project), entry(gone)),
                List.of(new GroupView(root, null, "我的密碼", "", false, T),
                        new GroupView(work, root, "工作", "", false, T),
                        new GroupView(project, work, "專案", "", false, T),
                        new GroupView(bin, root, "資源回收桶", "", true, T),
                        new GroupView(gone, bin, "舊群組", "", false, T),
                        new GroupView(home, root, "家庭", "", false, T)));

        List<GroupRows.Row> rows = GroupRows.of(data);

        assertEquals(List.of(GroupRows.Kind.ALL, GroupRows.Kind.GROUP, GroupRows.Kind.GROUP, GroupRows.Kind.GROUP,
                GroupRows.Kind.BIN, GroupRows.Kind.RECYCLED),
                rows.stream().map(GroupRows.Row::kind).collect(Collectors.toList()));
        assertEquals(List.of("", "工作", "專案", "家庭", "", "舊群組"),
                rows.stream().map(GroupRows.Row::name).collect(Collectors.toList()));
        assertEquals(List.of(0, 0, 1, 0, 0, 1), rows.stream().map(GroupRows.Row::depth).collect(Collectors.toList()));
        // 數字＝點進去會列出的條目數：全部條目（不含回收桶）、群組只算直接放在裡面的、回收桶算裡面全部
        assertEquals(List.of(4, 1, 2, 0, 1, 1), rows.stream().map(GroupRows.Row::count).collect(Collectors.toList()));
        assertEquals(bin, rows.get(4).groupId());
        assertEquals(gone, rows.get(5).groupId());
    }

    @Test
    public void fileWithoutRecycleBinStillShowsEmptyBin() {
        UUID root = UUID.randomUUID();
        ScreenData data = ScreenData.of(List.of(), List.of(new GroupView(root, null, "空的", "", false, T)));

        List<GroupRows.Row> rows = GroupRows.of(data);

        assertEquals(2, rows.size());
        assertEquals(GroupRows.Kind.BIN, rows.get(1).kind());
        assertEquals(null, rows.get(1).groupId());
        assertEquals(0, rows.get(1).count());
    }

    private static EntryView entry(UUID group) {
        return new EntryView(UUID.randomUUID(), group, "x", "", "", "", "", Map.of(), List.of(), "", T, T, 0);
    }
}
