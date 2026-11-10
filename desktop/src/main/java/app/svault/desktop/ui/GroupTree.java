package app.svault.desktop.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 左側群組樹（GroupTree）：JavaFX 原生 {@link TreeView}，方向鍵選取；條目可拖放到群組上
 * （AC-02-4）；右鍵或鍵盤的選單鍵（{@code Shift+F10}）開啟選取群組的選單。{@code F2}、{@code Delete} 由畫面以
 * {@link Shortcut} 綁定。每列：圖示＋名稱＋條目數（fg-muted）。
 */
public final class GroupTree extends TreeView<GroupTree.Item> {

    /** 拖放的條目：內容是條目 UUID 的字串。 */
    public static final DataFormat ENTRY = new DataFormat("application/x-svault-entry");

    private static final PseudoClass DROP_TARGET = PseudoClass.getPseudoClass("drop-target");

    /** 群組的種類。 */
    public enum Kind {
        /** 根群組（名稱是保管庫名稱）。 */
        ROOT,
        /** 一般群組。 */
        GROUP,
        /** 資源回收桶本身（固定在最下面）。 */
        RECYCLE_BIN,
        /** 資源回收桶裡的群組。 */
        RECYCLED;

        /** @return 可以把條目拖進來 */
        public boolean acceptsEntries() {
            return this == ROOT || this == GROUP;
        }
    }

    /**
     * 樹的一個節點。
     *
     * @param id    群組 UUID 字串；還不存在的資源回收桶為空字串
     * @param name  顯示名稱
     * @param count 條目數（含子群組）
     * @param kind  種類
     */
    public record Item(String id, String name, int count, Kind kind) {

        /** 群組名稱是使用者的內容，不輸出（CONVENTIONS §2）。 */
        @Override
        public String toString() {
            return "GroupTree.Item[" + id + "]";
        }
    }

    private BiConsumer<String, Item> onDropEntry = (entry, target) -> { };
    private Runnable onRename = () -> { };
    private Function<Item, List<MenuItem>> menuFactory = item -> List.of();
    private final ContextMenu menu = new ContextMenu();

    /** 建立空的群組樹。 */
    public GroupTree() {
        getStyleClass().add("sv-group-tree");
        setShowRoot(true);
        setAccessibleText(UiText.get("group.tree"));
        setCellFactory(view -> new Cell());
        // TreeView 自己把 F2 當成「開始編輯」吃掉（就算不可編輯），場景層的快捷鍵收不到；在這裡先接住
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (Shortcut.RENAME_GROUP.combination().match(e)) {
                onRename.run();
                e.consume();
            }
        });
        setOnContextMenuRequested(e -> {
            TreeItem<Item> selected = getSelectionModel().getSelectedItem();
            List<MenuItem> items = selected == null ? List.of() : menuFactory.apply(selected.getValue());
            if (items.isEmpty()) {
                return;
            }
            menu.getItems().setAll(items);
            menu.show(this, e.getScreenX(), e.getScreenY());
            e.consume();
        });
    }

    /**
     * @param handler 焦點在群組樹時按 {@code F2}（重新命名選取的群組）
     */
    public void setOnRename(Runnable handler) {
        onRename = handler;
    }

    /**
     * @param handler 條目拖放到群組上時（條目 UUID 字串、目標群組）
     */
    public void setOnDropEntry(BiConsumer<String, Item> handler) {
        onDropEntry = handler;
    }

    /**
     * @param factory 選取群組的選單項目；沒有可做的事時回傳空清單
     */
    public void setMenuFactory(Function<Item, List<MenuItem>> factory) {
        menuFactory = factory;
    }

    /**
     * 讓清單的列可以拖到群組上：在列的 {@code DRAG_DETECTED} 呼叫。
     *
     * @param source  拖曳的來源節點
     * @param entryId 條目 UUID 字串
     */
    public static void startEntryDrag(Node source, String entryId) {
        Dragboard db = source.startDragAndDrop(TransferMode.MOVE);
        ClipboardContent content = new ClipboardContent();
        content.put(ENTRY, entryId);
        db.setContent(content);
    }

    /** 一列：圖示＋名稱＋條目數；可以當拖放目標。 */
    private final class Cell extends TreeCell<Item> {
        private final Region icon = Icons.of(IconName.FOLDER);
        private final Label name = new Label();
        private final Label count = new Label();
        private final Tooltip tip = new Tooltip();
        private final HBox box;

        Cell() {
            name.getStyleClass().add("sv-group-name");
            count.getStyleClass().add("sv-caption");
            count.setMinWidth(Region.USE_PREF_SIZE);
            // 名稱本身佔滿剩下的寬度（原本是名稱＋彈性空白）：筆數變寬或模組寫入後重用 cell 時，名稱照舊的偏好寬度被當成
            // 上限而顯示成「Chrom…」（M-03 完整驗證，T-111）；改成名稱自己伸縮，只有真的放不下才以刪節號縮短
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            box = new HBox(icon, name, count);
            box.getStyleClass().add("sv-group-row");
            // cell 寬度跟著樹（不依內容撐寬）：樹收窄時名稱以刪節號縮短，條目數仍看得到
            name.setMinWidth(0);
            setPrefWidth(0);
            setOnDragOver(e -> {
                if (accepts(e.getDragboard())) {
                    e.acceptTransferModes(TransferMode.MOVE);
                }
                e.consume();
            });
            setOnDragEntered(e -> pseudoClassStateChanged(DROP_TARGET, accepts(e.getDragboard())));
            setOnDragExited(e -> pseudoClassStateChanged(DROP_TARGET, false));
            setOnDragDropped(e -> {
                boolean done = false;
                if (accepts(e.getDragboard())) {
                    onDropEntry.accept((String) e.getDragboard().getContent(ENTRY), getItem());
                    done = true;
                }
                e.setDropCompleted(done);
                e.consume();
            });
        }

        private boolean accepts(Dragboard db) {
            return getItem() != null && getItem().kind().acceptsEntries() && db.hasContent(ENTRY);
        }

        @Override
        protected void updateItem(Item item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setTooltip(null);
                setText(null);
                setAccessibleText(null);
                return;
            }
            Icons.set(icon, item.kind() == Kind.RECYCLE_BIN ? IconName.DELETE : IconName.FOLDER);
            name.setText(item.name());
            // 預設寬度（DESIGN §4 的 220）下長名稱加大筆數仍可能縮成刪節號：游標停留時顯示完整名稱（M-04 完整驗證）
            tip.setText(item.name());
            count.setText(UiText.format("group.count", item.count()));
            setText(null);
            setGraphic(box);
            setTooltip(tip);
            setAccessibleText(UiText.format("group.accessible", item.name(), item.count()));
        }
    }
}
