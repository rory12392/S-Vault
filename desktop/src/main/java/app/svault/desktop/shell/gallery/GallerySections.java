package app.svault.desktop.shell.gallery;

import app.svault.desktop.shell.AppShell;
import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.DiffRow;
import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.EntryRow;
import app.svault.desktop.ui.FieldRow;
import app.svault.desktop.ui.FormField;
import app.svault.desktop.ui.GroupTree;
import app.svault.desktop.ui.IconButton;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.PasswordInput;
import app.svault.desktop.ui.SearchField;
import app.svault.desktop.ui.SettingsRow;
import app.svault.desktop.ui.StrengthMeter;
import app.svault.desktop.ui.StrengthMeter.Strength;
import app.svault.desktop.ui.SvButton;
import app.svault.desktop.ui.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.animation.PauseTransition;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Duration;

/**
 * 展示頁右欄的各個區塊：每個元件一區，列出變體與狀態。
 */
final class GallerySections {

    private final AppShell shell;
    private final ResourceBundle text;

    GallerySections(AppShell shell, ResourceBundle text) {
        this.shell = shell;
        this.text = text;
    }

    List<Node> all() {
        List<Node> out = new ArrayList<>();
        out.add(section("section.typography", typography()));
        out.add(section("section.buttons", buttons()));
        out.add(section("section.iconButtons", iconButtons()));
        out.add(section("section.textFields", textFields()));
        out.add(section("section.password", password()));
        out.add(section("section.strength", strength()));
        out.add(section("section.search", new SearchField()));
        out.add(section("section.entryRow", entryRows()));
        out.add(section("section.groupTree", groupTree()));
        out.add(section("section.fieldRow", fieldRows()));
        out.add(section("section.dialog", dialogs()));
        out.add(section("section.toast", toasts()));
        out.add(section("section.banner", banners()));
        out.add(section("section.progress", progress()));
        out.add(section("section.empty", empties()));
        out.add(section("section.diff", diffs()));
        out.add(section("section.settings", settings()));
        return out;
    }

    static ConfirmDialog dangerDialog(AppShell shell, ResourceBundle text) {
        return UiGallery.sampleDialog(shell, text, ConfirmDialog.Variant.DANGER);
    }

    private Node section(String key, Node... body) {
        Label title = new Label(text.getString(key));
        title.getStyleClass().add("sv-section-title");
        VBox box = new VBox(title);
        for (Node n : body) {
            if (n instanceof VBox v && v.getStyleClass().isEmpty()) {
                v.getStyleClass().add("sv-stack");
            }
            box.getChildren().add(n);
        }
        box.getStyleClass().add("sv-gallery-section");
        return box;
    }

    private Node typography() {
        Label page = label("type.page", "sv-page-title");
        Label sectionTitle = label("type.section", "sv-section-title");
        Label body = label("type.body", null);
        Label caption = label("type.caption", "sv-caption");
        Label path = label("type.path", null);
        Label mono = label("type.mono", "sv-mono");
        // 中文字型驗證（待驗證）：顯示實際使用的字型名稱
        Label font = new Label(String.format(text.getString("type.fontInfo"), Font.getDefault().getName(),
                body.getFont().getName(), body.getFont().getFamily()));
        font.getStyleClass().add("sv-caption");
        body.fontProperty().addListener((o, a, f) -> font.setText(String.format(text.getString("type.fontInfo"),
                Font.getDefault().getName(), f.getName(), f.getFamily())));
        return new VBox(page, sectionTitle, body, caption, path, mono, font);
    }

    private Node buttons() {
        SvButton loading = SvButton.primary(text.getString("button.saving"));
        loading.setLoading(true);
        SvButton disabled = SvButton.secondary(text.getString("button.disabled"));
        disabled.setDisable(true);
        SvButton toggleLoading = SvButton.secondary(text.getString("button.toggleLoading"));
        SvButton target = SvButton.primary(text.getString("button.primary"));
        toggleLoading.setOnAction(e -> target.setLoading(!target.isLoading()));
        return flow(target, SvButton.secondary(text.getString("button.secondary")),
                SvButton.ghost(text.getString("button.ghost")), SvButton.danger(text.getString("button.danger")),
                loading, disabled, toggleLoading);
    }

    private Node iconButtons() {
        IconButton disabled = new IconButton(IconName.DELETE, text.getString("icon.delete"));
        disabled.setDisable(true);
        return flow(new IconButton(IconName.CONTENT_COPY, text.getString("icon.copy")),
                new IconButton(IconName.VISIBILITY, text.getString("icon.show")),
                new IconButton(IconName.LOCK, text.getString("icon.lock")),
                new IconButton(IconName.EDIT, text.getString("icon.edit")),
                new IconButton(IconName.SETTINGS, text.getString("icon.settings")), disabled);
    }

    private Node textFields() {
        FormField normal = FormField.text(text.getString("field.title"));
        normal.textField().setText("GitHub");
        FormField error = FormField.text(text.getString("field.url"));
        error.textField().setText("htp:/example");
        error.setError(text.getString("field.urlError"));
        FormField disabled = FormField.text(text.getString("field.disabled"));
        disabled.textField().setText(text.getString("field.disabledValue"));
        disabled.input().setDisable(true);
        FormField prompt = FormField.text(text.getString("field.username"));
        prompt.textField().setPromptText(text.getString("field.usernamePrompt"));
        return new VBox(normal, prompt, error, disabled);
    }

    private Node password() {
        PasswordInput plain = new PasswordInput(false);
        plain.maskedField().setText("probe1234");
        PasswordInput revealed = new PasswordInput(true);
        revealed.maskedField().setText("l1O0-Il|o0");
        revealed.setRevealed(true);
        revealed.meter().setStrength(Strength.MEDIUM);
        PasswordInput error = new PasswordInput(false);
        FormField errorField = FormField.password(text.getString("password.master"), error);
        errorField.setError(text.getString("password.wrong"));
        return new VBox(FormField.password(text.getString("password.label"), plain),
                FormField.password(text.getString("password.withMeter"), revealed), errorField);
    }

    private Node strength() {
        VBox box = new VBox();
        for (Strength s : Strength.values()) {
            StrengthMeter m = new StrengthMeter();
            m.setStrength(s);
            box.getChildren().add(m);
        }
        return box;
    }

    private Node entryRows() {
        EntryRow plain = new EntryRow(() -> { });
        plain.show("GitHub", "demo-user", "");
        EntryRow hit = new EntryRow(() -> { });
        hit.show("Google Workspace", "demo@example.com", "goo");
        EntryRow noUser = new EntryRow(() -> { });
        noUser.show(text.getString("sample.wifi"), "", "");
        EntryRow found = new EntryRow(() -> { });
        found.show("GitHub", "demo-user", "git", text.getString("sample.groupPath"));
        VBox box = new VBox(plain, hit, noUser, found);
        box.getStyleClass().add("sv-card");
        return box;
    }

    private Node groupTree() {
        TreeItem<GroupTree.Item> root = groupItem("root", "groupTree.root", 6, GroupTree.Kind.ROOT);
        TreeItem<GroupTree.Item> home = groupItem("home", "groupTree.home", 1, GroupTree.Kind.GROUP);
        home.getChildren().add(groupItem("bank", "groupTree.bank", 1, GroupTree.Kind.GROUP));
        root.getChildren().setAll(List.of(groupItem("work", "groupTree.work", 2, GroupTree.Kind.GROUP), home,
                groupItem("bin", "groupTree.bin", 1, GroupTree.Kind.RECYCLE_BIN)));
        GroupTree tree = new GroupTree();
        tree.setRoot(root);
        tree.getSelectionModel().select(1);
        tree.setPrefHeight(220);
        VBox box = new VBox(tree);
        box.getStyleClass().addAll("sv-card", "sv-column-groups");
        return box;
    }

    private TreeItem<GroupTree.Item> groupItem(String id, String key, int count, GroupTree.Kind kind) {
        TreeItem<GroupTree.Item> item = new TreeItem<>(new GroupTree.Item(id, text.getString(key), count, kind));
        item.setExpanded(true);
        return item;
    }

    private Node fieldRows() {
        FieldRow user = new FieldRow(FieldRow.Kind.TEXT, text.getString("fieldRow.username"),
                () -> "demo@example.com", () -> { }, null);
        FieldRow[] pw = new FieldRow[1];
        pw[0] = new FieldRow(FieldRow.Kind.PASSWORD, text.getString("fieldRow.password"), () -> "probe1234",
                () -> pw[0].markCopied(), null);
        FieldRow url = new FieldRow(FieldRow.Kind.URL, text.getString("fieldRow.url"),
                () -> "https://example.com/login", () -> { }, () -> { });
        FieldRow notes = new FieldRow(FieldRow.Kind.NOTES, text.getString("fieldRow.notes"),
                () -> text.getString("fieldRow.notesValue"), () -> { }, null);
        FieldRow copied = new FieldRow(FieldRow.Kind.TEXT, text.getString("fieldRow.copied"),
                () -> "demo-user", () -> { }, null);
        copied.markCopied();
        FieldRow revealed = new FieldRow(FieldRow.Kind.PASSWORD, text.getString("fieldRow.revealed"),
                () -> "l1O0-Il|o0", () -> { }, null);
        revealed.setRevealed(true);
        VBox box = new VBox(user, pw[0], revealed, url, notes, copied);
        box.getStyleClass().add("sv-card");
        return box;
    }

    private Node dialogs() {
        SvButton normal = SvButton.secondary(text.getString("dialog.openDefault"));
        normal.setOnAction(e -> UiGallery.sampleDialog(normal, text, ConfirmDialog.Variant.DEFAULT).showAndWait());
        SvButton danger = SvButton.secondary(text.getString("dialog.openDanger"));
        danger.setOnAction(e -> UiGallery.sampleDialog(danger, text, ConfirmDialog.Variant.DANGER).showAndWait());
        SvButton submitting = SvButton.secondary(text.getString("dialog.openSubmitting"));
        submitting.setOnAction(e -> {
            ConfirmDialog d = UiGallery.sampleDialog(submitting, text, ConfirmDialog.Variant.DANGER);
            // 展示「送出中」：按下確認後 2 秒才關閉
            d.confirmButton().addEventFilter(ActionEvent.ACTION, ev -> {
                ev.consume();
                d.setSubmitting(true);
                PauseTransition done = new PauseTransition(Duration.seconds(2));
                done.setOnFinished(x -> d.dialog().close());
                done.play();
            });
            d.showAndWait();
        });
        return flow(normal, danger, submitting);
    }

    private Node toasts() {
        SvButton info = SvButton.secondary(text.getString("toast.showInfo"));
        info.setOnAction(e -> shell.toasts().show(Toast.Kind.INFO, text.getString("toast.info")));
        SvButton ok = SvButton.secondary(text.getString("toast.showSuccess"));
        ok.setOnAction(e -> shell.toasts().show(Toast.Kind.SUCCESS, text.getString("toast.success")));
        SvButton err = SvButton.secondary(text.getString("toast.showError"));
        err.setOnAction(e -> shell.toasts().show(Toast.Kind.ERROR, text.getString("toast.error")));
        SvButton sticky = SvButton.secondary(text.getString("toast.showSticky"));
        sticky.setOnAction(e -> shell.toasts().showUntilClosed(Toast.Kind.INFO, text.getString("toast.clipboard")));
        return flow(info, ok, err, sticky);
    }

    private Node banners() {
        return new VBox(new Banner(Banner.Kind.INFO, text.getString("banner.info")),
                new Banner(Banner.Kind.WARNING, text.getString("banner.warning")),
                new Banner(Banner.Kind.ERROR, text.getString("banner.error"), text.getString("banner.action"),
                        () -> { }));
    }

    private Node progress() {
        SvButton spin = SvButton.secondary(text.getString("progress.showSpinner"));
        spin.setOnAction(e -> {
            shell.progress().show(text.getString("progress.unlocking"));
            hideLater();
        });
        SvButton pct = SvButton.secondary(text.getString("progress.showPercent"));
        pct.setOnAction(e -> {
            shell.progress().show(text.getString("overlay.text"), 0.42);
            hideLater();
        });
        return flow(spin, pct);
    }

    private void hideLater() {
        PauseTransition t = new PauseTransition(Duration.seconds(2));
        t.setOnFinished(x -> shell.progress().hide());
        t.play();
    }

    private Node empties() {
        EmptyState withAction = new EmptyState(IconName.FOLDER, text.getString("empty.title"),
                text.getString("empty.body"), text.getString("empty.action"), () -> { });
        EmptyState noAction = new EmptyState(IconName.SEARCH, text.getString("empty.notFound"), null, null, null);
        VBox box = new VBox(withAction, noAction);
        box.getStyleClass().add("sv-card");
        return box;
    }

    private Node diffs() {
        String thisTime = text.getString("diff.thisTime");
        String otherTime = text.getString("diff.otherTime");
        DiffRow none = new DiffRow(text.getString("diff.password"), new DiffRow.Choice("demo-pc-1234", thisTime),
                new DiffRow.Choice("demo-phone-5678", otherTime), true);
        DiffRow revealed = new DiffRow(text.getString("diff.password"), new DiffRow.Choice("demo-pc-1234", thisTime),
                new DiffRow.Choice("demo-phone-5678", otherTime), true);
        revealed.setRevealed(true);
        revealed.select(DiffRow.Side.THIS_DEVICE);
        DiffRow chosen = new DiffRow(text.getString("diff.username"), new DiffRow.Choice("demo@example.com", thisTime),
                new DiffRow.Choice("demo@outlook.com", otherTime), false);
        chosen.select(DiffRow.Side.OTHER_DEVICE);
        DiffRow empty = new DiffRow(text.getString("diff.notes"), "", text.getString("diff.notesValue"));
        empty.setMissing(true);
        return new VBox(none, revealed, chosen, empty);
    }

    private Node settings() {
        SettingsRow disabled = SettingsRow.toggle(text.getString("settings.disabled"), null);
        disabled.setDisable(true);
        VBox box = new VBox(SettingsRow.toggle(text.getString("settings.toggle"), text.getString("settings.toggleBody")),
                SettingsRow.choice(text.getString("settings.choice"), null, List.of(text.getString("theme.system"),
                        text.getString("theme.light"), text.getString("theme.dark"))),
                SettingsRow.number(text.getString("settings.number"), text.getString("settings.numberBody"), 1, 60, 5),
                SettingsRow.navigation(text.getString("settings.nav"), null, () -> { }), disabled);
        box.getStyleClass().add("sv-card");
        return box;
    }

    private Label label(String key, String styleClass) {
        Label l = new Label(text.getString(key));
        l.setWrapText(true);
        if (styleClass != null) {
            l.getStyleClass().add(styleClass);
        }
        return l;
    }

    private static FlowPane flow(Node... nodes) {
        FlowPane pane = new FlowPane(nodes);
        pane.getStyleClass().add("sv-flow");
        return pane;
    }
}
