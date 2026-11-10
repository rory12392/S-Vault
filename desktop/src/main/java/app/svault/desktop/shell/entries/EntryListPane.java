package app.svault.desktop.shell.entries;

import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.EntryRow;
import app.svault.desktop.ui.GroupTree;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.SearchField;
import app.svault.desktop.ui.SvButton;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Function;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 中間欄：SearchField＋「新增」、（有時）Banner、條目清單或空狀態（AC-02-6、AC-03-3）。
 * 清單列是共用的 {@link EntryRow}（列高固定 48，2,000 筆只建立看得到的 cell）。
 */
final class EntryListPane extends VBox {

    /** 清單的動作。 */
    interface Actions {
        /** 新增條目。 */
        void add();

        /** @param entry 選到的條目（{@code null}＝沒有選取） */
        void selected(EntryView entry);

        /** @param entry 要開啟（Enter、雙擊：焦點移到詳細） */
        void open(EntryView entry);

        /** @param entry 要複製密碼的條目 */
        void copyPassword(EntryView entry);
    }

    private final ResourceBundle text;
    private final Actions actions;
    private final SearchField search = new SearchField();
    private final SvButton add;
    private final VBox banners = new VBox();
    private final ListView<EntryView> list = new ListView<>();
    private final StackPane body = new StackPane();
    private Function<EntryView, String> pathOf = e -> null;
    /** 程式設定選取時不通知（避免換清單內容時觸發「捨棄修改？」）。 */
    private boolean updating;

    /**
     * @param text    畫面文字
     * @param actions 動作
     */
    EntryListPane(ResourceBundle text, Actions actions) {
        this.text = text;
        this.actions = actions;
        getStyleClass().add("sv-list-pane");
        search.field().setPromptText(text.getString("list.search"));
        search.field().setId("search");
        add = SvButton.secondary(text.getString("list.add"));
        add.setGraphic(Icons.of(IconName.ADD));
        add.setId("add-entry");
        add.setOnAction(e -> actions.add());
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox header = new HBox(search, add);
        header.getStyleClass().add("sv-list-header");

        list.setId("entry-list");
        list.getStyleClass().add("sv-entry-list");
        list.setAccessibleText(text.getString("list.accessible"));
        list.setCellFactory(view -> cell());
        list.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (!updating) {
                actions.selected(now);
            }
        });
        list.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            EntryView selected = list.getSelectionModel().getSelectedItem();
            if (e.getCode() == KeyCode.ENTER && selected != null) {
                actions.open(selected);
                e.consume();
            }
        });
        banners.getStyleClass().add("sv-banner-slot");
        banners.managedProperty().bind(banners.visibleProperty());
        banners.setVisible(false);
        VBox.setVgrow(body, Priority.ALWAYS);
        body.getChildren().add(list);
        getChildren().addAll(header, banners, body);
    }

    private ListCell<EntryView> cell() {
        ListCell<EntryView> c = EntryRow.cell(EntryView::title, EntryView::username, e -> pathOf.apply(e),
                search.textProperty()::get, actions::copyPassword);
        c.setOnDragDetected(e -> {
            if (c.getItem() != null) {
                GroupTree.startEntryDrag(c, c.getItem().uuid().toString());
                e.consume();
            }
        });
        c.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && c.getItem() != null) {
                actions.open(c.getItem());
            }
        });
        return c;
    }

    /** @return 搜尋欄 */
    SearchField search() {
        return search;
    }

    /** @return 條目清單 */
    ListView<EntryView> list() {
        return list;
    }

    /** @return 「新增」按鈕 */
    SvButton addButton() {
        return add;
    }

    /** 解鎖後第一次載入中（ScreenData 在背景建立）。 */
    void showLoading() {
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.getStyleClass().add("sv-inline-spinner");
        Label label = new Label(text.getString("list.loading"));
        label.getStyleClass().add("sv-caption");
        VBox loading = new VBox(spinner, label);
        loading.getStyleClass().add("sv-list-loading");
        loading.setId("list-loading");
        body.getChildren().setAll(loading);
    }

    /**
     * @param listing  要列出的內容
     * @param pathOf   搜尋結果的群組路徑
     * @param select   要選取的條目；{@code null} 時不選
     * @param onAddNew 空狀態的「新增條目」
     */
    void show(Listing listing, Function<EntryView, String> pathOf, UUID select, Runnable onAddNew) {
        this.pathOf = listing.search() ? pathOf : e -> null;
        updating = true;
        try {
            list.getItems().setAll(listing.entries());
            list.getSelectionModel().clearSelection();
            if (select != null) {
                for (int i = 0; i < listing.entries().size(); i++) {
                    if (listing.entries().get(i).uuid().equals(select)) {
                        list.getSelectionModel().select(i);
                        list.scrollTo(Math.max(0, i - 3));
                        break;
                    }
                }
            }
        } finally {
            updating = false;
        }
        // 每列內容（關鍵字標示、群組路徑）跟著搜尋文字變，可見的 cell 重畫一次
        list.refresh();
        Node empty = empty(listing.empty(), onAddNew);
        if (empty == null) {
            body.getChildren().setAll(list);
        } else {
            body.getChildren().setAll(empty);
        }
    }

    /**
     * 不通知的選取（「捨棄修改？」選了繼續編輯時，把清單的選取改回編輯中的條目）。
     *
     * @param entryId 條目；{@code null} 時清除選取
     */
    void selectSilently(UUID entryId) {
        updating = true;
        try {
            list.getSelectionModel().clearSelection();
            for (int i = 0; entryId != null && i < list.getItems().size(); i++) {
                if (list.getItems().get(i).uuid().equals(entryId)) {
                    list.getSelectionModel().select(i);
                    break;
                }
            }
        } finally {
            updating = false;
        }
    }

    /** @param banner 清單上方的 Banner；{@code null} 時收起 */
    void setBanner(Node banner) {
        banners.getChildren().setAll(banner == null ? new Node[0] : new Node[] {banner});
        banners.setVisible(banner != null);
    }

    /** @param busy 存檔中：不能新增（畫面可繼續瀏覽與搜尋，DESIGN §6） */
    void setBusy(boolean busy) {
        add.setDisable(busy);
    }

    /** 焦點移到清單；清單是空的時候移到空狀態的「新增條目」（有的話）。 */
    void focusDefault() {
        if (!body.getChildren().isEmpty() && body.getChildren().get(0) instanceof EmptyState s
                && s.action() != null) {
            s.action().requestFocus();
        } else {
            list.requestFocus();
        }
    }

    /** @return 清單中選到的條目 */
    EntryView selected() {
        return list.getSelectionModel().getSelectedItem();
    }

    private Node empty(Listing.Empty kind, Runnable onAddNew) {
        EmptyState state = switch (kind) {
            case NONE -> null;
            case VAULT -> new EmptyState(IconName.KEY, text.getString("list.empty.vault.title"),
                    text.getString("list.empty.vault.description"), text.getString("list.empty.action"), onAddNew);
            case GROUP -> new EmptyState(IconName.FOLDER, text.getString("list.empty.group.title"), null,
                    text.getString("list.empty.action"), onAddNew);
            case RECYCLE_BIN -> new EmptyState(IconName.DELETE, text.getString("list.empty.bin.title"),
                    text.getString("list.empty.bin.description"), null, null);
            case NO_RESULTS -> new EmptyState(IconName.SEARCH, text.getString("list.empty.search.title"), null, null,
                    null);
        };
        if (state != null) {
            state.setId("list-empty");
            if (state.action() != null) {
                state.action().setId("empty-add");
            }
        }
        return state;
    }
}
