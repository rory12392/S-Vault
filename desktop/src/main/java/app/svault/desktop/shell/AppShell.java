package app.svault.desktop.shell;

import app.svault.desktop.ui.IconButton;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.ProgressOverlay;
import app.svault.desktop.ui.Shortcut;
import app.svault.desktop.ui.ShortcutRegistry;
import app.svault.desktop.ui.SvButton;
import app.svault.desktop.ui.Toast;
import java.util.EnumSet;
import java.util.List;
import java.util.ResourceBundle;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 主視窗外框。
 *
 * <ul>
 *   <li>未解鎖（歡迎、建立、解鎖）：{@link #showCentered}，單欄置中、最大寬度 480，不顯示工具列。</li>
 *   <li>解鎖後：{@link #showThreeColumns}，群組樹（220，可拖拉 180–320）｜條目清單（360）｜詳細（其餘），
 *       上方工具列。視窗寬小於 1100 時群組樹收合成工具列上的按鈕，{@code Ctrl+G} 展開／收合。</li>
 * </ul>
 * 最上層依序是畫面、{@link ProgressOverlay}、{@link Toast.Host}。
 */
public final class AppShell extends StackPane {

    /** 最小視窗。 */
    public static final double MIN_WIDTH = 900;
    /** 最小視窗高度。 */
    public static final double MIN_HEIGHT = 600;
    /** 群組樹自動收合的寬度門檻。 */
    public static final double COLLAPSE_BELOW = 1100;

    private static final double CENTER_MAX_WIDTH = 480;
    private static final double GROUP_WIDTH = 220;
    private static final double GROUP_MIN = 180;
    private static final double GROUP_MAX = 320;
    private static final double LIST_WIDTH = 360;

    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.messages");
    private final BorderPane frame = new BorderPane();
    private final ProgressOverlay progress = new ProgressOverlay(frame);
    private final Toast.Host toasts = new Toast.Host();
    private final HBox toolbar = new HBox();
    private final Label vaultName = new Label();
    private final Label syncStatus = new Label();
    private final IconButton groupToggle;
    private final SvButton lockButton;
    private final MenuButton menu = new MenuButton();
    private final SplitPane columns = new SplitPane();

    private Node groupPane;
    private Region listPane;
    private Region detailPane;
    /** {@link #showPage} 的頁面；沒有時 {@code null}。 */
    private Region pagePane;
    /** 開頁面前的焦點元件，關掉頁面時還給它。 */
    private Node returnFocus;
    private boolean groupsHiddenByUser;

    /** 建立空的外框；畫面由 {@link #showCentered} 或 {@link #showThreeColumns} 放入。 */
    public AppShell() {
        getStyleClass().add("sv-app-shell");
        frame.getStyleClass().add("sv-app-frame");

        groupToggle = new IconButton(IconName.MENU, text.getString("toolbar.groups"));
        groupToggle.setOnAction(e -> toggleGroups());
        vaultName.getStyleClass().add("sv-toolbar-title");
        syncStatus.getStyleClass().add("sv-toolbar-status");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        lockButton = SvButton.secondary(text.getString("toolbar.lock"));
        lockButton.setGraphic(Icons.of(IconName.LOCK));
        menu.setGraphic(Icons.of(IconName.MORE_VERT));
        menu.getStyleClass().add("sv-toolbar-menu");
        menu.setId("toolbar-menu");
        menu.setAccessibleText(text.getString("toolbar.menu"));
        toolbar.getStyleClass().add("sv-toolbar");
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getChildren().addAll(groupToggle, vaultName, syncStatus, spacer, lockButton, menu);

        columns.getStyleClass().add("sv-columns");
        widthProperty().addListener((obs, old, now) -> applyCollapse());
        getChildren().addAll(frame, progress, toasts);
    }

    /**
     * 綁定外框的快捷鍵（{@code Ctrl+G}、{@code Ctrl+L}）。場景建立後呼叫一次。
     *
     * @param scene  主視窗場景
     * @param onLock 上鎖動作（AC-12-5）
     */
    public void bindShortcuts(Scene scene, Runnable onLock) {
        lockButton.setOnAction(e -> onLock.run());
        ShortcutRegistry registry = ShortcutRegistry.of(scene);
        registry.bind(Shortcut.TOGGLE_GROUPS, this::toggleGroups);
        registry.bind(Shortcut.LOCK, onLock);
    }

    /**
     * 未解鎖的畫面：單欄置中。
     *
     * @param content 畫面內容
     */
    public void showCentered(Node content) {
        VBox column = new VBox(content);
        column.getStyleClass().add("sv-centered-column");
        column.setMaxWidth(CENTER_MAX_WIDTH);
        column.setMaxHeight(Region.USE_PREF_SIZE);
        column.setFillWidth(true);
        StackPane holder = new StackPane(column);
        holder.getStyleClass().add("sv-centered");
        StackPane.setAlignment(column, Pos.TOP_CENTER);
        // 建立保管庫的表單在最小視窗（900×600）放不下，超出時整頁捲動
        ScrollPane scroll = new ScrollPane(holder);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("sv-centered-scroll");
        frame.setTop(null);
        frame.setCenter(scroll);
        dropPage();
        groupPane = null;
        listPane = null;
        detailPane = null;
    }

    /** 換掉整個畫面（上鎖、重新載入）時：頁面直接丟掉，暫停的快捷鍵恢復。 */
    private void dropPage() {
        if (pagePane != null && getScene() != null) {
            ShortcutRegistry.of(getScene()).resume();
        }
        pagePane = null;
        returnFocus = null;
    }

    /**
     * 解鎖後的三欄。
     *
     * @param groups 群組樹
     * @param list   條目清單（含上方 SearchField 與「新增」按鈕）
     * @param detail 詳細／編輯
     */
    public void showThreeColumns(Node groups, Node list, Node detail) {
        dropPage();
        groupPane = wrap(groups, "sv-column-groups", GROUP_MIN, GROUP_WIDTH, GROUP_MAX);
        listPane = wrap(list, "sv-column-list", LIST_WIDTH, LIST_WIDTH, Double.MAX_VALUE);
        detailPane = wrap(detail, "sv-column-detail", 0, Region.USE_COMPUTED_SIZE, Double.MAX_VALUE);
        SplitPane.setResizableWithParent(groupPane, false);
        SplitPane.setResizableWithParent(listPane, false);
        columns.getItems().setAll(groupPane, listPane, detailPane);
        frame.setTop(toolbar);
        frame.setCenter(columns);
        applyCollapse();
    }

    /**
     * 在右側兩欄（條目清單＋詳細）的位置顯示一個頁面（設定、功能模組的 {@code MenuPage}）。
     * 頁面開著時群組樹停用，條目畫面的快捷鍵暫停（只留上鎖與設定），避免在看不到的清單或編輯畫面上動作。
     *
     * @param content 頁面內容
     */
    public void showPage(Node content) {
        if (listPane == null) {
            return;
        }
        closePage();
        returnFocus = getScene() == null ? null : getScene().getFocusOwner();
        pagePane = wrap(content, "sv-column-page", 0, Region.USE_COMPUTED_SIZE, Double.MAX_VALUE);
        // 先加入頁面並把焦點移過去，再移除原本的兩欄：焦點元件被移出場景時，JavaFX 會在下一個 pulse
        // 把焦點交給第一個可聚焦的元件（窄視窗時是工具列的「顯示群組」），蓋掉頁面自己設定的焦點
        columns.getItems().add(pagePane);
        applyCss();
        layout();
        pagePane.requestFocus();
        columns.getItems().removeAll(listPane, detailPane);
        if (groupPane != null) {
            groupPane.setDisable(true);
        }
        if (getScene() != null) {
            ShortcutRegistry.of(getScene()).suspendExcept(EnumSet.of(Shortcut.LOCK, Shortcut.SETTINGS));
        }
    }

    /** 關掉 {@link #showPage} 的頁面，回到條目清單與詳細；沒有開著的頁面時不做事。 */
    public void closePage() {
        if (pagePane == null) {
            return;
        }
        columns.getItems().addAll(listPane, detailPane);
        if (groupPane != null) {
            groupPane.setDisable(false);
        }
        // 同 showPage：焦點先回到開頁面前的元件（通常是條目清單），再移除頁面
        applyCss();
        layout();
        if (returnFocus != null && returnFocus.getScene() == getScene()) {
            returnFocus.requestFocus();
        } else {
            listPane.requestFocus();
        }
        returnFocus = null;
        columns.getItems().remove(pagePane);
        pagePane = null;
        // 頁面移掉之後才設分隔線：頁面還在時有四欄，照三欄算的位置會讓群組樹只剩最小寬度（M-04 完整驗證，T-111 沒修到的原因）
        restoreWidths();
        if (getScene() != null) {
            ShortcutRegistry.of(getScene()).resume();
        }
    }

    /** @return 是否有 {@link #showPage} 的頁面開著 */
    public boolean pageOpen() {
        return pagePane != null;
    }

    /**
     * 工具列上的保管庫名稱與同步狀態文字。
     *
     * @param name   保管庫名稱
     * @param status 同步狀態（例：「已同步」）；沒有時空字串
     */
    public void setVaultStatus(String name, String status) {
        vaultName.setText(name);
        syncStatus.setText(status);
    }

    /**
     * 工具列選單（產生器、健檢、匯入、備份還原、設定、關於；功能模組的 {@code MenuPage} 也加在這裡）。
     *
     * @param items 選單項目
     */
    public void setMenuItems(List<MenuItem> items) {
        menu.getItems().setAll(items);
    }

    /** @return 覆蓋進度 */
    public ProgressOverlay progress() {
        return progress;
    }

    /** @return Toast 覆蓋層 */
    public Toast.Host toasts() {
        return toasts;
    }

    /** @return 群組樹目前是否顯示 */
    public boolean groupsVisible() {
        return groupPane != null && columns.getItems().contains(groupPane);
    }

    /** 展開／收合群組樹（{@code Ctrl+G}、工具列按鈕）。 */
    public void toggleGroups() {
        if (groupPane == null) {
            return;
        }
        if (groupsVisible()) {
            groupsHiddenByUser = true;
            columns.getItems().remove(groupPane);
        } else {
            groupsHiddenByUser = false;
            columns.getItems().add(0, groupPane);
            restoreWidths();
        }
        updateToggle();
    }

    /**
     * 群組樹重新加進來時，分隔線回到 DESIGN §4 的預設寬度（群組 220、清單 360）；不然 SplitPane 沿用兩欄時的
     * 位置，窄視窗下群組樹只分到最小寬度，名稱被擠成刪節號。
     */
    private void restoreWidths() {
        double w = columns.getWidth();
        if (w > GROUP_WIDTH + LIST_WIDTH) {
            columns.setDividerPositions(GROUP_WIDTH / w, (GROUP_WIDTH + LIST_WIDTH) / w);
        }
    }

    private void applyCollapse() {
        if (groupPane == null) {
            return;
        }
        boolean narrow = getWidth() > 0 && getWidth() < COLLAPSE_BELOW;
        boolean shouldShow = !narrow && !groupsHiddenByUser;
        if (shouldShow && !groupsVisible()) {
            columns.getItems().add(0, groupPane);
        } else if (!shouldShow && groupsVisible() && narrow) {
            columns.getItems().remove(groupPane);
        }
        updateToggle();
    }

    private void updateToggle() {
        // 群組樹沒有顯示時，工具列的按鈕是唯一的入口
        boolean show = groupPane != null && !groupsVisible();
        groupToggle.setVisible(show);
        groupToggle.setManaged(show);
    }

    private static Region wrap(Node content, String styleClass, double min, double pref, double max) {
        StackPane pane = new StackPane(content);
        pane.getStyleClass().addAll("sv-column", styleClass);
        pane.setMinWidth(min);
        pane.setPrefWidth(pref);
        pane.setMaxWidth(max);
        return pane;
    }
}
