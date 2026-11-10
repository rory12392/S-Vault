package app.svault.desktop.shell.entries;

import app.svault.core.shell.ScreenData;
import app.svault.core.vault.GroupView;
import app.svault.desktop.ui.GroupTree;
import java.util.Set;
import java.util.UUID;
import javafx.scene.control.TreeItem;

/**
 * 由 {@link ScreenData} 建立群組樹的節點：根群組＝保管庫名稱，底下依檔案順序的群組，
 * 「資源回收桶」固定在最後（裡面被刪除的群組也列出，可以還原）。檔案還沒有回收桶時放一個空的回收桶節點。
 */
final class GroupItems {

    private GroupItems() {
    }

    /**
     * @param data      目前的內容
     * @param binName   資源回收桶的顯示名稱
     * @param collapsed 使用者收合的群組 id（重建後維持）
     * @return 根節點
     */
    static TreeItem<GroupTree.Item> build(ScreenData data, String binName, Set<String> collapsed) {
        GroupView root = data.root();
        // 根群組的條目數不含回收桶（countInGroup 會算進回收桶裡的）
        TreeItem<GroupTree.Item> top = node(new GroupTree.Item(root.uuid().toString(), root.name(),
                data.query().all().size(), GroupTree.Kind.ROOT), collapsed);
        UUID binId = data.recycleBin().map(GroupView::uuid).orElse(null);
        for (GroupView child : data.children(root.uuid())) {
            if (!child.uuid().equals(binId)) {
                top.getChildren().add(subtree(data, child, GroupTree.Kind.GROUP, collapsed));
            }
        }
        TreeItem<GroupTree.Item> bin = node(new GroupTree.Item(binId == null ? "" : binId.toString(), binName,
                data.query().recycled().size(), GroupTree.Kind.RECYCLE_BIN), collapsed);
        if (binId != null) {
            for (GroupView child : data.children(binId)) {
                bin.getChildren().add(subtree(data, child, GroupTree.Kind.RECYCLED, collapsed));
            }
        }
        top.getChildren().add(bin);
        return top;
    }

    /**
     * @param top 根節點
     * @param id  群組 id（資源回收桶還不存在時是空字串）
     * @return 節點；找不到時 {@code null}
     */
    static TreeItem<GroupTree.Item> find(TreeItem<GroupTree.Item> top, String id) {
        if (top.getValue().id().equals(id)) {
            return top;
        }
        for (TreeItem<GroupTree.Item> child : top.getChildren()) {
            TreeItem<GroupTree.Item> found = find(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * @param item 群組樹上選到的節點
     * @return 清單要列的範圍（資源回收桶還不存在時，回收桶範圍的群組是 {@code null}）
     */
    static Listing.Scope scopeOf(GroupTree.Item item) {
        if (item.kind() == GroupTree.Kind.RECYCLE_BIN) {
            return Listing.Scope.bin(item.id().isEmpty() ? null : UUID.fromString(item.id()));
        }
        return Listing.Scope.group(UUID.fromString(item.id()));
    }

    private static TreeItem<GroupTree.Item> subtree(ScreenData data, GroupView group, GroupTree.Kind kind,
            Set<String> collapsed) {
        TreeItem<GroupTree.Item> item = node(new GroupTree.Item(group.uuid().toString(), group.name(),
                data.query().countInGroup(group.uuid()), kind), collapsed);
        for (GroupView child : data.children(group.uuid())) {
            item.getChildren().add(subtree(data, child, kind, collapsed));
        }
        return item;
    }

    private static TreeItem<GroupTree.Item> node(GroupTree.Item value, Set<String> collapsed) {
        TreeItem<GroupTree.Item> item = new TreeItem<>(value);
        item.setExpanded(!collapsed.contains(value.id()));
        item.expandedProperty().addListener((obs, old, now) -> {
            if (now) {
                collapsed.remove(value.id());
            } else {
                collapsed.add(value.id());
            }
        });
        return item;
    }
}
