package app.svault.desktop.shell.start;

import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.SvButton;
import java.io.File;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 歡迎／最近開啟（US-01、US-06）：Logo＋「S-Vault」→ 最近開啟清單 →「開啟檔案」「建立新的保管庫」。
 * 清單的 Enter／雙擊開啟、Delete 從清單移除（不刪檔）；可把 {@code .kdbx} 拖放進來開啟。
 */
final class WelcomeScreen extends VBox {

    /** 畫面上的動作（由 {@link StartFlow} 實作）。 */
    interface Actions {
        void openRecent(RecentItem item);

        void forget(RecentItem item);

        void openFile();

        void chooseAgain();

        void create();

        void openDropped(Path file);
    }

    // 清單最多同時顯示 4 列，其餘捲動；900×600 時按鈕仍在畫面內
    private static final int VISIBLE_ROWS = 4;
    private static final double ROW_HEIGHT = 56;

    private final ResourceBundle text;
    private final Actions actions;
    private final VBox problemSlot = new VBox();
    private final VBox recentSlot = new VBox();
    private final ListView<RecentItem> list = new ListView<>();
    private final SvButton open;

    WelcomeScreen(ResourceBundle text, Actions actions) {
        this.text = text;
        this.actions = actions;
        getStyleClass().addAll("sv-form", "sv-welcome");

        HBox brand = new HBox(Icons.large(IconName.LOCK), title(text.getString("app.name")));
        brand.getStyleClass().add("sv-brand");

        list.setId("welcome-recent");
        list.getStyleClass().add("sv-recent-list");
        list.setAccessibleText(text.getString("welcome.recent.accessible"));
        list.setFixedCellSize(ROW_HEIGHT);
        list.setCellFactory(v -> new RecentCell());
        list.setOnKeyPressed(e -> {
            RecentItem selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            if (e.getCode() == KeyCode.ENTER) {
                actions.openRecent(selected);
                e.consume();
            } else if (e.getCode() == KeyCode.DELETE) {
                actions.forget(selected);
                e.consume();
            }
        });
        // 沒有訊息時不佔位置（否則表單多一段間距）
        problemSlot.managedProperty().bind(Bindings.isNotEmpty(problemSlot.getChildren()));

        open = SvButton.primary(text.getString("welcome.open"));
        open.setId("welcome-open");
        open.setOnAction(e -> actions.openFile());
        SvButton create = SvButton.secondary(text.getString("welcome.create"));
        create.setId("welcome-create");
        create.setOnAction(e -> actions.create());
        HBox buttons = new HBox(open, create);
        buttons.getStyleClass().add("sv-button-row");

        getChildren().addAll(brand, problemSlot, recentSlot, buttons);
        acceptDrops();
    }

    /** @param items 最近開啟的保管庫（最新的在前）；空清單時顯示 EmptyState */
    void setRecent(List<RecentItem> items) {
        list.getItems().setAll(items);
        if (items.isEmpty()) {
            recentSlot.getChildren().setAll(new EmptyState(IconName.FOLDER, text.getString("welcome.empty.title"),
                    text.getString("welcome.empty.description"), null, null));
            return;
        }
        list.setPrefHeight(Math.min(items.size(), VISIBLE_ROWS) * ROW_HEIGHT + 2);
        list.getSelectionModel().selectFirst();
        Label heading = new Label(text.getString("welcome.recent"));
        heading.getStyleClass().add("sv-section-title");
        heading.setLabelFor(list);
        Label hint = new Label(text.getString("welcome.recent.hint"));
        hint.getStyleClass().add("sv-caption");
        hint.setWrapText(true);
        VBox box = new VBox(heading, list, hint);
        box.getStyleClass().add("sv-form-field");
        recentSlot.getChildren().setAll(box);
    }

    /**
     * 檔案讀不到等原因（AC-06-4）：error Banner＋「重新選擇檔案」。
     *
     * @param problem 呈現方式；{@code null} 清除
     */
    void showProblem(OpenProblem problem) {
        if (problem == null) {
            problemSlot.getChildren().clear();
            return;
        }
        Banner banner = problem.chooseAgain()
                ? new Banner(Banner.Kind.ERROR, text.getString(problem.messageKey()),
                        text.getString("open.chooseAgain"), actions::chooseAgain)
                : new Banner(Banner.Kind.ERROR, text.getString(problem.messageKey()));
        banner.setId("welcome-problem");
        problemSlot.getChildren().setAll(banner);
    }

    /** 進入畫面時的焦點：有最近清單時在清單，否則在「開啟檔案」。 */
    void focusDefault() {
        if (list.getItems().isEmpty()) {
            open.requestFocus();
        } else {
            list.requestFocus();
        }
    }

    private void acceptDrops() {
        setOnDragOver(e -> {
            if (e.getDragboard().hasFiles() && droppedVault(e.getDragboard().getFiles()) != null) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        setOnDragDropped(e -> {
            Path file = e.getDragboard().hasFiles() ? droppedVault(e.getDragboard().getFiles()) : null;
            if (file != null) {
                actions.openDropped(file);
            }
            e.setDropCompleted(file != null);
            e.consume();
        });
    }

    private static Path droppedVault(List<File> files) {
        for (File f : files) {
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(CreateForm.EXTENSION) && f.isFile()) {
                return f.toPath();
            }
        }
        return null;
    }

    private static Label title(String value) {
        Label label = new Label(value);
        label.getStyleClass().add("sv-page-title");
        return label;
    }

    private final class RecentCell extends ListCell<RecentItem> {

        private final Label name = new Label();
        private final Label opened = new Label();
        private final Label path = new Label();
        private final VBox box;

        RecentCell() {
            getStyleClass().add("sv-recent-cell");
            name.getStyleClass().add("sv-recent-name");
            opened.getStyleClass().add("sv-caption");
            path.getStyleClass().add("sv-caption");
            // 路徑太長時省略中間，頭（磁碟機）和尾（檔名）都看得到
            path.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
            path.setMinWidth(0);
            name.setMinWidth(0);
            opened.setMinWidth(Region.USE_PREF_SIZE);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox top = new HBox(name, spacer, opened);
            top.getStyleClass().add("sv-recent-top");
            box = new VBox(top, path);
            box.getStyleClass().add("sv-recent-row");
            setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && getItem() != null) {
                    actions.openRecent(getItem());
                }
            });
        }

        @Override
        protected void updateItem(RecentItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            name.setText(item.name());
            opened.setText(MessageFormat.format(text.getString("welcome.recent.opened"), item.lastOpened()));
            path.setText(item.file().toString());
            setAccessibleText(MessageFormat.format(text.getString("welcome.recent.item"), item.name(),
                    item.file().toString(), item.lastOpened()));
            setGraphic(box);
        }
    }
}
