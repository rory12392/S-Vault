package app.svault.desktop.shell.entries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.shell.ScreenData;
import app.svault.core.sync.MergeNotice;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.desktop.ui.GroupTree;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.UUID;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 條目畫面的固定文字（依驗收條件逐字比對）與群組樹的組成。
 */
class EntriesTextTest {

    private static final ResourceBundle TEXT = ResourceBundle.getBundle("app.svault.desktop.shell.entries.messages");
    private static final Instant T = Instant.parse("2026-10-18T00:00:00Z");

    @Test
    @DisplayName("AC-02-6、AC-03-3：空狀態與找不到的文字逐字比對")
    void emptyTexts() {
        assertEquals("這個群組還沒有條目", TEXT.getString("list.empty.group.title"));
        assertEquals("建立你的第一筆條目", TEXT.getString("list.empty.vault.title"));
        assertEquals("新增條目", TEXT.getString("list.empty.action"));
        assertEquals("找不到符合的條目", TEXT.getString("list.empty.search.title"));
        assertEquals("搜尋名稱、帳號或網址", TEXT.getString("list.search"));
    }

    @Test
    @DisplayName("AC-02-3：刪除確認、捨棄修改、儲存與複製的文字")
    void dialogTexts() {
        assertEquals("刪除這筆條目？", TEXT.getString("confirm.deleteEntry.title"));
        assertEquals("刪除", TEXT.getString("confirm.deleteEntry.confirm"));
        assertEquals("捨棄修改？", TEXT.getString("edit.discard.title"));
        assertEquals("你的修改還沒有儲存。", TEXT.getString("edit.discard.body"));
        assertEquals("捨棄", TEXT.getString("edit.discard.confirm"));
        assertEquals("繼續編輯", TEXT.getString("edit.discard.keep"));
        assertEquals("名稱不能是空的", TEXT.getString("edit.error.name"));
        assertEquals("已儲存", TEXT.getString("toast.saved"));
        // AC-05-1：秒數跟著設定（T-19；10–300，預設 30）
        assertEquals("已複製密碼，30 秒後清除",
                java.text.MessageFormat.format(TEXT.getString("toast.copiedPassword"), 30L));
        assertEquals("已複製密碼，300 秒後清除",
                java.text.MessageFormat.format(TEXT.getString("toast.copiedPassword"), 300L));
        assertEquals("已複製帳號", TEXT.getString("toast.copiedUsername"));
    }

    @Test
    @DisplayName("AC-07-9：每種合併通知都有一則與多則的文字；整份取代的文字逐字比對")
    void mergeNoticeTexts() {
        for (MergeNotice.Kind kind : MergeNotice.Kind.values()) {
            assertTrue(TEXT.containsKey("notice." + kind.name() + ".one"), kind.name());
            assertTrue(TEXT.containsKey("notice." + kind.name() + ".many"), kind.name());
        }
        assertEquals("雲端檔案被另一台裝置的版本整份取代，已保留兩邊的條目（同一筆以較新的為準，舊版本在歷史紀錄）",
                TEXT.getString("notice.REMOTE_REPLACED.one"));
    }

    @Test
    @DisplayName("群組樹：根群組（條目數不含回收桶）→ 群組 → 資源回收桶固定在最後，裡面被刪的群組也列出")
    void treeShape() {
        UUID root = UUID.randomUUID();
        UUID work = UUID.randomUUID();
        UUID bin = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        ScreenData data = ScreenData.of(List.of(entry(root), entry(work), entry(gone)), List.of(
                new GroupView(root, null, "我的密碼", "", false, T),
                new GroupView(work, root, "工作", "", false, T),
                new GroupView(bin, root, "資源回收桶", "", true, T),
                new GroupView(gone, bin, "舊群組", "", false, T),
                new GroupView(later, root, "家庭", "", false, T)));

        TreeItem<GroupTree.Item> top = GroupItems.build(data, "資源回收桶", new HashSet<>());

        assertEquals(new GroupTree.Item(root.toString(), "我的密碼", 2, GroupTree.Kind.ROOT), top.getValue());
        assertEquals(List.of("工作", "家庭", "資源回收桶"),
                top.getChildren().stream().map(i -> i.getValue().name()).toList());
        TreeItem<GroupTree.Item> binItem = top.getChildren().get(2);
        assertEquals(GroupTree.Kind.RECYCLE_BIN, binItem.getValue().kind());
        assertEquals(1, binItem.getValue().count());
        assertEquals(GroupTree.Kind.RECYCLED, binItem.getChildren().get(0).getValue().kind());
        assertEquals(binItem.getChildren().get(0), GroupItems.find(top, gone.toString()));
    }

    @Test
    @DisplayName("檔案還沒有資源回收桶時仍顯示一個空的回收桶節點")
    void virtualBin() {
        UUID root = UUID.randomUUID();
        ScreenData data = ScreenData.of(List.of(), List.of(new GroupView(root, null, "空的", "", false, T)));

        TreeItem<GroupTree.Item> top = GroupItems.build(data, "資源回收桶", new HashSet<>());

        GroupTree.Item bin = top.getChildren().get(0).getValue();
        assertEquals(new GroupTree.Item("", "資源回收桶", 0, GroupTree.Kind.RECYCLE_BIN), bin);
    }

    private static EntryView entry(UUID group) {
        return new EntryView(UUID.randomUUID(), group, "x", "", "", "", "", Map.of(), List.of(), "", T, T, 0);
    }
}
