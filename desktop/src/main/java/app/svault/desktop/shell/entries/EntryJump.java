package app.svault.desktop.shell.entries;

import app.svault.core.shell.EntryTarget;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.desktop.ui.GroupTree;
import app.svault.desktop.ui.Toast;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.scene.control.TreeItem;

/**
 * desktop 的「以 UUID 開啟條目」（{@code EntryNavigator}，T-30；Architecture §6）：關掉模組頁、清空搜尋，群組樹選到
 * 條目所在的群組（回收桶裡的條目選到回收桶或回收桶裡的群組），清單選到條目、右欄顯示詳細，焦點在清單。
 */
final class EntryJump {

    private static final Logger LOG = Logger.getLogger(EntryJump.class.getName());

    private EntryJump() {
    }

    /**
     * JavaFX 執行緒。
     *
     * @param screen  解鎖後的三欄
     * @param entryId 條目
     */
    static void open(VaultScreen screen, UUID entryId) {
        EntryTarget target = EntryTarget.resolve(screen.data(), entryId);
        switch (target.kind()) {
            case UNAVAILABLE -> {
                // 解鎖後還在載入（約 0.2 秒）：模組頁通常還沒開；不排隊等，使用者再按一次即可
                LOG.log(Level.FINE, "內容還在載入，不開啟條目");
                return;
            }
            case NOT_FOUND -> {
                // 模組頁留著（例如健檢報告），使用者可以重新整理報告
                screen.shell().toasts().show(Toast.Kind.ERROR, screen.text().getString("toast.entryNotFound"));
                return;
            }
            default -> { }
        }
        screen.shell().closePage();
        // 編輯中有未儲存的修改：先確認「捨棄修改？」；選「繼續編輯」就停在編輯畫面（模組頁已關掉）
        if (!screen.editFlow().leaveEdit()) {
            return;
        }
        EntryView entry = target.entry();
        if (!screen.notSearching()) {
            // 清空搜尋會以目前的群組重列一次；接著才換群組
            screen.list().search().textProperty().set("");
        }
        screen.selectGroup(groupNode(screen, entry, target.kind() == EntryTarget.Kind.RECYCLED), false);
        screen.relist(entry.uuid());
        screen.list().focusDefault();
    }

    /** 群組樹上的節點 id：條目所在的群組；樹上沒有時（不應發生）回收桶裡的條目選回收桶，其他選根群組。 */
    private static String groupNode(VaultScreen screen, EntryView entry, boolean recycled) {
        String id = entry.groupId().toString();
        TreeItem<GroupTree.Item> root = screen.tree().getRoot();
        if (root != null && GroupItems.find(root, id) != null) {
            return id;
        }
        if (recycled) {
            return screen.data().recycleBin().map(GroupView::uuid).map(UUID::toString).orElse("");
        }
        return screen.data().root().uuid().toString();
    }
}
