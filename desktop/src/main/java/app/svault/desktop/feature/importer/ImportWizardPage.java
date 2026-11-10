package app.svault.desktop.feature.importer;

import app.svault.core.feature.importer.ImportPlan;
import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.feature.importer.ImportWizardModel.Busy;
import app.svault.core.feature.importer.ImportWizardModel.PreviewRow;
import app.svault.core.feature.importer.ImportWizardModel.Problem;
import app.svault.core.feature.importer.ImportWizardModel.Step;
import app.svault.core.module.ModuleGuard;
import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.ProgressOverlay;
import app.svault.desktop.ui.SvButton;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 「從 Chrome 匯入」頁的內容：頂部步驟（1 選擇檔案 → 2 預覽 → 3 完成），依
 * {@link ImportWizardModel} 的狀態重畫。外殼給頁首與「返回」，這裡排邊距與捲動；讀檔與匯入中用
 * {@link ProgressOverlay}；錯誤用 error Banner（模組拿不到外殼的 Toast，D-IMP-05）。
 *
 * <p>預覽的密碼一律顯示固定長度的遮罩（AC-14-1）。頁面從場景移除時放掉 CSV 的內容（{@link ImportWizardModel#clear()}）。
 */
final class ImportWizardPage extends StackPane {

    /** 預覽的密碼欄：固定長度，不透露密碼長度。 */
    static final String MASK = "••••••••";
    /** 行號清單最多列幾個，其餘以「…」代替（幾千列格式錯誤時不把頁面撐爆）。 */
    static final int MAX_LINES_SHOWN = 20;

    private final ImportWizardModel model;
    private final ResourceBundle text;
    private final CsvPicker picker;
    private final ModuleGuard guard;
    private final VBox body = new VBox();
    private final ScrollPane scroll = new ScrollPane(body);
    private final ProgressOverlay overlay = new ProgressOverlay(scroll);
    private Step rendered;

    ImportWizardPage(ImportWizardModel model, ResourceBundle text, CsvPicker picker, ModuleGuard guard) {
        this.model = model;
        this.text = text;
        this.picker = picker;
        this.guard = guard;
        setId("imp-page");
        getStylesheets().add(ImportWizardPage.class.getResource("importer.css").toExternalForm());
        body.getStyleClass().add("sv-imp-page");
        scroll.setFitToWidth(true);
        // 開頁時焦點要落在內容裡的控制項，不是捲動區本身（外殼找第一個可 Tab 到的元件）
        scroll.setFocusTraversable(false);
        scroll.getStyleClass().add("sv-settings-scroll");
        getChildren().addAll(scroll, overlay);
        model.setListener(() -> guard.run(ImporterDesktopFeature.ID, "render", this::render));
        // 焦點移到捲動區外時捲過去，改由模組頁外框統一處理（T-112，ScrollFollow）
        sceneProperty().addListener((obs, before, after) -> {
            if (after == null && before != null) {
                model.clear();
            }
        });
        render();
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private void render() {
        Node focus = null;
        List<Node> children = new ArrayList<>();
        children.add(steps());
        model.problem().ifPresent(p -> {
            Banner banner = new Banner(Banner.Kind.ERROR, text.getString(problemKey(p)));
            banner.setId("imp-problem");
            children.add(banner);
        });
        switch (model.step()) {
            case CHOOSE -> focus = choose(children);
            case PREVIEW -> focus = preview(children);
            case DONE -> focus = done(children);
            default -> throw new IllegalStateException(model.step().name());
        }
        body.getChildren().setAll(children);
        if (model.busy() == Busy.NONE) {
            overlay.hide();
        } else {
            overlay.show(model.busy() == Busy.READING ? text.getString("progress.reading")
                    : MessageFormat.format(text.getString("progress.importing"), model.toImport()));
        }
        // 換步驟或忙完時把焦點放在這一步的主要按鈕（覆蓋層停用內容時 JavaFX 會把焦點移到外框）
        if (model.busy() == Busy.NONE && focus != null && (rendered != model.step() || isFocusLost())) {
            focus.requestFocus();
        }
        if (model.busy() == Busy.NONE) {
            rendered = model.step();
        }
    }

    private boolean isFocusLost() {
        return getScene() == null || getScene().getFocusOwner() == null
                || !isInside(getScene().getFocusOwner(), this);
    }

    /** 頂部的步驟（目前這一步粗體＋primary；螢幕閱讀器讀「第 n 步，共 3 步」）。 */
    private HBox steps() {
        HBox row = new HBox();
        row.getStyleClass().add("sv-imp-steps");
        Step[] all = Step.values();
        for (int i = 0; i < all.length; i++) {
            Label label = new Label(text.getString("step." + key(all[i])));
            label.getStyleClass().add("sv-imp-step");
            if (all[i] == model.step()) {
                label.getStyleClass().add("sv-imp-step-current");
                label.setAccessibleText(MessageFormat.format(text.getString("step.current"), i + 1, all.length,
                        label.getText()));
            }
            row.getChildren().add(label);
            if (i < all.length - 1) {
                Label arrow = new Label("→");
                arrow.getStyleClass().add("sv-imp-step");
                row.getChildren().add(arrow);
            }
        }
        row.setId("imp-steps");
        return row;
    }

    private Node choose(List<Node> children) {
        Label intro = wrapped(text.getString("choose.intro"), null);
        Label how = wrapped(text.getString("choose.how"), "sv-caption");
        SvButton pick = SvButton.primary(text.getString("choose.button"));
        pick.setId("imp-choose");
        pick.setOnAction(e -> guard.run(ImporterDesktopFeature.ID, "choose",
                () -> picker.pick(getScene() == null ? null : getScene().getWindow()).map(CsvFile::new).ifPresent(model::open)));
        children.addAll(List.of(intro, how, actions(pick)));
        return pick;
    }

    private Node preview(List<Node> children) {
        Label total = new Label(MessageFormat.format(text.getString("preview.total"), model.total()));
        total.getStyleClass().add("sv-section-title");
        total.setId("imp-total");
        Label source = wrapped(model.name(), "sv-caption");
        children.add(new VBox(total, source));
        if (model.total() > 0) {
            Label caption = wrapped(MessageFormat.format(text.getString("preview.first"),
                    model.preview().size()), "sv-caption");
            children.add(new VBox(caption, table(model.preview())));
        }
        List<Integer> skipped = model.skippedFormatLines();
        if (!skipped.isEmpty()) {
            Banner banner = new Banner(Banner.Kind.WARNING, MessageFormat.format(text.getString("preview.skipped"),
                    skipped.size(), lines(skipped)));
            banner.setId("imp-skipped");
            children.add(banner);
        }
        Node choice = duplicates();
        if (choice != null) {
            children.add(choice);
        }
        SvButton again = SvButton.secondary(text.getString("preview.rechoose"));
        again.setId("imp-rechoose");
        again.setOnAction(e -> model.restart());
        SvButton go = SvButton.primary(MessageFormat.format(text.getString("preview.import"),
                ImportPlan.GROUP_NAME));
        go.setId("imp-import");
        go.setDefaultButton(true);
        go.setDisable(model.toImport() == 0);
        go.setOnAction(e -> model.importNow());
        if (model.toImport() == 0) {
            children.add(wrapped(text.getString("preview.nothing"), "sv-caption"));
        }
        children.add(actions(again, go));
        return go.isDisable() ? again : go;
    }

    /** 前 10 筆：名稱、帳號、網址、密碼（遮罩）、標記。 */
    private GridPane table(List<PreviewRow> rows) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("sv-imp-table");
        grid.setId("imp-preview");
        String[] heads = {"preview.col.name", "preview.col.username", "preview.col.url", "preview.col.password", ""};
        for (int c = 0; c < heads.length; c++) {
            Label head = new Label(heads[c].isEmpty() ? "" : text.getString(heads[c]));
            head.getStyleClass().add("sv-imp-head");
            grid.add(head, c, 0);
        }
        for (int r = 0; r < rows.size(); r++) {
            PreviewRow row = rows.get(r);
            grid.add(cell(row.name()), 0, r + 1);
            grid.add(cell(row.username()), 1, r + 1);
            grid.add(cell(row.url()), 2, r + 1);
            Label mask = cell(MASK);
            mask.setAccessibleText(text.getString("preview.masked"));
            grid.add(mask, 3, r + 1);
            if (row.duplicate()) {
                Label tag = new Label(text.getString("preview.duplicate"));
                tag.getStyleClass().add("sv-imp-tag");
                tag.setMinWidth(USE_PREF_SIZE);
                grid.add(tag, 4, r + 1);
            }
        }
        // 名稱、帳號、網址、密碼；剩下的給「可能重複」標記
        int[] percents = {26, 22, 30, 10};
        for (int p : percents) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(p);
            col.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(col);
        }
        ColumnConstraints tagCol = new ColumnConstraints();
        tagCol.setMinWidth(USE_PREF_SIZE);
        grid.getColumnConstraints().add(tagCol);
        return grid;
    }

    /** 可能重複的說明與選擇（AC-14-4）；沒有可能重複的列時為 {@code null}。 */
    private Node duplicates() {
        List<Integer> dup = model.duplicateLines();
        if (dup.isEmpty()) {
            return null;
        }
        Label what = wrapped(MessageFormat.format(text.getString("duplicate.intro"), dup.size(), lines(dup)), null);
        what.setId("imp-duplicates");
        ToggleGroup group = new ToggleGroup();
        RadioButton skip = new RadioButton(MessageFormat.format(text.getString("duplicate.skip"), dup.size()));
        skip.setId("imp-dup-skip");
        RadioButton keep = new RadioButton(text.getString("duplicate.keep"));
        keep.setId("imp-dup-keep");
        skip.setToggleGroup(group);
        keep.setToggleGroup(group);
        group.selectToggle(model.skipDuplicates() ? skip : keep);
        group.selectedToggleProperty().addListener((obs, before, after) -> {
            if (after != null) {
                model.setSkipDuplicates(after == skip);
            }
        });
        VBox box = new VBox(what, skip, keep);
        box.getStyleClass().add("sv-imp-section");
        return box;
    }

    private Node done(List<Node> children) {
        Label title = new Label(MessageFormat.format(text.getString("done.title"), model.imported()));
        title.getStyleClass().add("sv-section-title");
        title.setId("imp-done");
        Label where = wrapped(MessageFormat.format(text.getString("done.group"), ImportPlan.GROUP_NAME),
                "sv-caption");
        Banner warning = new Banner(Banner.Kind.WARNING, text.getString("done.warning"));
        warning.setId("imp-delete-csv");
        Label pathTitle = new Label(text.getString("done.path"));
        pathTitle.getStyleClass().add("sv-imp-head");
        Label path = wrapped(model.location(), "sv-imp-path");
        path.setId("imp-path");
        path.setAccessibleText(text.getString("done.path") + " " + path.getText());
        SvButton again = SvButton.secondary(text.getString("done.again"));
        again.setId("imp-again");
        again.setOnAction(e -> model.restart());
        children.addAll(List.of(new VBox(title, where), warning, new VBox(pathTitle, path), actions(again)));
        return again;
    }

    private static Label cell(String value) {
        Label l = new Label(value);
        l.getStyleClass().add("sv-imp-cell");
        l.setMaxWidth(Double.MAX_VALUE);
        return l;
    }

    private static Label wrapped(String value, String styleClass) {
        Label l = new Label(value);
        l.setWrapText(true);
        if (styleClass != null) {
            l.getStyleClass().add(styleClass);
        }
        return l;
    }

    private static HBox actions(SvButton... buttons) {
        HBox row = new HBox(buttons);
        row.getStyleClass().add("sv-button-row");
        return row;
    }

    /**
     * @param lines 行號
     * @return 「3、17」；超過 {@link #MAX_LINES_SHOWN} 個時列前面的再加「…」
     */
    static String lines(List<Integer> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.size(), MAX_LINES_SHOWN); i++) {
            if (i > 0) {
                sb.append('、');
            }
            sb.append(lines.get(i));
        }
        if (lines.size() > MAX_LINES_SHOWN) {
            sb.append("、…");
        }
        return sb.toString();
    }

    private static String key(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * @param p 錯誤
     * @return 它在 {@code messages.properties} 的鍵
     */
    static String problemKey(Problem p) {
        return "problem." + key(p);
    }
}
