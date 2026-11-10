package app.svault.desktop.shell.gallery;

import app.svault.desktop.shell.AppShell;
import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.EntryRow;
import app.svault.desktop.ui.FormField;
import app.svault.desktop.ui.IconButton;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.PageHeader;
import app.svault.desktop.ui.PasswordInput;
import app.svault.desktop.ui.SearchField;
import app.svault.desktop.ui.Shortcut;
import app.svault.desktop.ui.ShortcutRegistry;
import app.svault.desktop.ui.SvButton;
import app.svault.desktop.ui.ThemeManager;
import app.svault.desktop.ui.ThemeMode;
import app.svault.desktop.ui.Toast;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javafx.application.ColorScheme;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 元件展示頁：以真的 {@link AppShell} 三欄呈現，右欄列出每個元件的變體與狀態，
 * 並可切換淺色／深色／跟隨系統。只用假資料。各元件的展示區塊在 {@link GallerySections}。
 */
public final class UiGallery {

    /** 展示用的假條目。 */
    record Sample(String title, String username) { }

    // 截圖時把右欄分成幾段捲動
    private static final double PAGES = 5;

    private final AppShell shell;
    private final ThemeManager theme;
    private final String state;
    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.gallery.messages");

    /**
     * @param shell 主視窗外框
     * @param theme 主題管理員
     * @param state 開啟時的狀態（{@code DevOptions.galleryState()}）
     */
    public UiGallery(AppShell shell, ThemeManager theme, String state) {
        this.shell = shell;
        this.theme = theme;
        this.state = state;
    }

    /** 放進外框並依 {@code state} 準備截圖用的狀態。 */
    public void install() {
        shell.setVaultStatus(text.getString("vault.name"), text.getString("vault.synced"));
        shell.setMenuItems(menuItems());
        if (state.equals("centered")) {
            shell.showCentered(centeredSample());
            return;
        }
        SearchField search = new SearchField();
        shell.showThreeColumns(groups(), entryList(search), details());
        Platform.runLater(() -> {
            if (shell.getScene() != null) {
                ShortcutRegistry.of(shell.getScene()).bind(Shortcut.SEARCH, search::focusField);
            }
        });
        switch (state) {
            case "toasts" -> {
                shell.toasts().showUntilClosed(Toast.Kind.INFO, text.getString("toast.info"));
                shell.toasts().showUntilClosed(Toast.Kind.SUCCESS, text.getString("toast.success"));
                shell.toasts().showUntilClosed(Toast.Kind.ERROR, text.getString("toast.error"));
            }
            case "overlay" -> shell.progress().show(text.getString("overlay.text"), 0.42);
            case "overlay-spin" -> shell.progress().show(text.getString("progress.unlocking"));
            case "dialog" -> Platform.runLater(() -> GallerySections.dangerDialog(shell, text).dialog().show());
            case "narrow-groups" -> Platform.runLater(shell::toggleGroups);
            case "keys" -> Platform.runLater(() -> new KeyboardCheck(shell.getScene(), shell).runAndExit());
            default -> { }
        }
    }

    private List<MenuItem> menuItems() {
        MenuItem settings = new MenuItem(text.getString("menu.settings"));
        settings.setAccelerator(Shortcut.SETTINGS.combination());
        return List.of(new MenuItem(text.getString("menu.generator")), new MenuItem(text.getString("menu.health")),
                new MenuItem(text.getString("menu.import")), new MenuItem(text.getString("menu.backups")),
                settings, new MenuItem(text.getString("menu.about")));
    }

    private Node groups() {
        TreeItem<String> root = new TreeItem<>(text.getString("group.all"));
        root.setExpanded(true);
        TreeItem<String> work = new TreeItem<>(text.getString("group.work"));
        work.getChildren().add(new TreeItem<>(text.getString("group.workServers")));
        work.setExpanded(true);
        root.getChildren().setAll(List.of(work, new TreeItem<>(text.getString("group.personal")),
                new TreeItem<>(text.getString("group.recycle"))));
        TreeView<String> tree = new TreeView<>(root);
        tree.getStyleClass().add("sv-group-tree");
        tree.getSelectionModel().select(root);
        tree.setAccessibleText(text.getString("group.accessible"));
        return tree;
    }

    private Node entryList(SearchField search) {
        FilteredList<Sample> items = new FilteredList<>(FXCollections.observableArrayList(
                new Sample("Google", "demo@example.com"),
                new Sample("GitHub", "demo-user"),
                new Sample(text.getString("sample.bank"), "A123456789"),
                new Sample(text.getString("sample.wifi"), ""),
                new Sample("Microsoft", "demo@outlook.com"),
                new Sample(text.getString("sample.school"), "s1100001")));
        ListView<Sample> list = new ListView<>(items);
        list.getStyleClass().add("sv-entry-list");
        list.setAccessibleText(text.getString("list.accessible"));
        list.setCellFactory(v -> EntryRow.cell(Sample::title, Sample::username, search.textProperty()::get,
                s -> shell.toasts().show(Toast.Kind.INFO, text.getString("toast.copied"))));
        search.textProperty().addListener((obs, old, q) -> {
            String query = q.strip().toLowerCase(Locale.ROOT);
            items.setPredicate(s -> query.isEmpty() || s.title().toLowerCase(Locale.ROOT).contains(query)
                    || s.username().toLowerCase(Locale.ROOT).contains(query));
        });
        list.getSelectionModel().select(1);
        VBox.setVgrow(list, Priority.ALWAYS);

        SvButton add = SvButton.secondary(text.getString("list.add"));
        add.setGraphic(Icons.of(IconName.ADD));
        HBox.setHgrow(search, Priority.ALWAYS);
        HBox top = new HBox(search, add);
        top.getStyleClass().add("sv-list-header");
        Banner sync = new Banner(Banner.Kind.INFO, text.getString("banner.syncing"));
        VBox box = new VBox(top, sync, list);
        box.getStyleClass().add("sv-list-pane");
        return box;
    }

    private Node details() {
        ToggleGroup modes = new ToggleGroup();
        HBox switcher = new HBox();
        switcher.getStyleClass().add("sv-segmented");
        for (ThemeMode m : ThemeMode.values()) {
            ToggleButton b = new ToggleButton(text.getString("theme." + m.settingValue()));
            b.setToggleGroup(modes);
            b.setUserData(m);
            b.setSelected(theme.modeProperty().get() == m);
            switcher.getChildren().add(b);
        }
        modes.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) {
                old.setSelected(true);
            } else {
                theme.modeProperty().set((ThemeMode) now.getUserData());
            }
        });
        IconButton lock = new IconButton(IconName.LOCK, text.getString("header.lock"));
        PageHeader header = new PageHeader(text.getString("header.title"), lock);
        Label effective = new Label();
        effective.getStyleClass().add("sv-caption");
        effective.textProperty().bind(Bindings.createStringBinding(() -> String.format(
                text.getString("theme.effective"), text.getString(theme.effectiveProperty().get() == ColorScheme.DARK
                        ? "theme.dark" : "theme.light")), theme.effectiveProperty()));
        HBox themeRow = new HBox(switcher, effective);
        themeRow.getStyleClass().add("sv-list-header");

        GallerySections sections = new GallerySections(shell, text);
        VBox content = new VBox(header, themeRow);
        content.getStyleClass().add("sv-gallery");
        content.getChildren().addAll(sections.all());
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sv-detail-scroll");
        if (state.startsWith("scroll-")) {
            // 截圖用：捲到第 n 頁（scroll-1 ～ scroll-5）
            double page = Double.parseDouble(state.substring("scroll-".length()));
            Platform.runLater(() -> scroll.setVvalue(Math.min(1, page / PAGES)));
        }
        return scroll;
    }

    private Node centeredSample() {
        Label title = new Label(text.getString("centered.title"));
        title.getStyleClass().add("sv-page-title");
        Label name = new Label(text.getString("centered.path"));
        name.getStyleClass().add("sv-caption");
        name.setWrapText(true);
        Banner error = new Banner(Banner.Kind.ERROR, text.getString("centered.kdf"));
        PasswordInput pw = new PasswordInput(false);
        FormField field = FormField.password(text.getString("centered.password"), pw);
        field.setError(text.getString("centered.wrong"));
        SvButton unlock = SvButton.primary(text.getString("centered.unlock"));
        unlock.setDefaultButton(true);
        unlock.setMaxWidth(Double.MAX_VALUE);
        SvButton other = SvButton.ghost(text.getString("centered.other"));
        VBox box = new VBox(title, name, error, field, unlock, other);
        box.getStyleClass().add("sv-form");
        return box;
    }

    static ConfirmDialog sampleDialog(Node owner, ResourceBundle text, ConfirmDialog.Variant variant) {
        return variant == ConfirmDialog.Variant.DANGER
                ? new ConfirmDialog(owner, text.getString("dialog.danger.title"), text.getString("dialog.danger.body"),
                        text.getString("dialog.danger.confirm"), variant)
                : new ConfirmDialog(owner, text.getString("dialog.default.title"),
                        text.getString("dialog.default.body"), text.getString("dialog.default.confirm"), variant);
    }
}
