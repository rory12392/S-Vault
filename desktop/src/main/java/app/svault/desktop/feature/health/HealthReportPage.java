package app.svault.desktop.feature.health;

import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.feature.health.HealthReportModel.Analysis;
import app.svault.core.feature.health.HealthReportModel.Breach;
import app.svault.core.feature.health.HealthReportModel.Row;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModuleGuard;
import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.IconButton;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.ProgressOverlay;
import app.svault.desktop.ui.SettingsRow;
import app.svault.desktop.ui.SvButton;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.UUID;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 健檢報告頁（T-52）：摘要（弱、重複、外洩）＋「檢查外洩」＋三個區段的清單。每列是條目名稱＋原因，
 * 點列或右側箭頭以 {@link EntryNavigator} 跳到條目（AC-13-1）。計算中用 {@link ProgressOverlay}；外洩檢查前
 * 一定先 {@link ConfirmDialog}（AC-13-2）；連線失敗時 warning Banner，弱／重複照常（AC-13-4）。
 *
 * <p>外框（PageHeader「密碼健檢」＋「返回」、Esc）由外殼的 {@code ModulePage} 給；這裡只排內容與捲動。
 * 頁面離開場景（返回、跳到條目、上鎖）時呼叫 {@code onClosed}，由模組中斷背景工作。
 */
final class HealthReportPage extends StackPane {

    private final HealthReportModel model;
    private final ResourceBundle text;
    private final EntryNavigator navigator;
    private final ModuleGuard guard;
    private final VBox body = new VBox();
    private final ScrollPane scroll = new ScrollPane(body);
    private final ProgressOverlay overlay = new ProgressOverlay(scroll);
    private final SvButton checkBreach;
    /** 計算開始時焦點在這一頁：算完把焦點放回「檢查外洩」。開頁時為 true（外殼把焦點放在內容裡）。 */
    private boolean restoreFocus = true;

    /**
     * @param model     報告的狀態
     * @param text      本模組的文字
     * @param navigator 跳到條目
     * @param guard     本模組的錯誤邊界（按鈕動作）
     * @param onClosed  頁面離開場景時（只呼叫一次）
     */
    HealthReportPage(HealthReportModel model, ResourceBundle text, EntryNavigator navigator, ModuleGuard guard,
            Runnable onClosed) {
        this.model = model;
        this.text = text;
        this.navigator = navigator;
        this.guard = guard;
        setId("hlt-page");
        body.getStyleClass().add("sv-settings-body");
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sv-settings-scroll");
        getChildren().addAll(scroll, overlay);

        checkBreach = SvButton.secondary(text.getString("breach.check"));
        checkBreach.setId("hlt-check-breach");
        checkBreach.setOnAction(e -> guard.run(HealthDesktopFeature.ID, "checkBreach", this::confirmBreach));

        model.setListener(new HealthReportModel.Listener() {
            @Override
            public void changed() {
                render();
            }

            @Override
            public void breachProgress(int done, int total) {
                showBreachProgress(done, total);
            }
        });
        sceneProperty().addListener((obs, before, after) -> {
            if (before != null && after == null) {
                onClosed.run();
            }
        });
        render();
    }

    /** 「檢查外洩」：先確認（會連網、只送雜湊前 5 碼），確認後才開始（AC-13-2）。 */
    private void confirmBreach() {
        ConfirmDialog dialog = new ConfirmDialog(this, text.getString("breach.confirm.title"),
                text.getString("breach.confirm.body"), text.getString("breach.confirm.ok"),
                ConfirmDialog.Variant.DEFAULT);
        dialog.dialog().getDialogPane().setId("hlt-breach-confirm");
        if (dialog.showAndWait()) {
            model.checkBreach();
        }
    }

    private void showBreachProgress(int done, int total) {
        if (model.breach() != Breach.RUNNING) {
            return;
        }
        String message = MessageFormat.format(text.getString("progress.breach"), done, total);
        overlay.show(message, total == 0 ? 0 : (double) done / total);
    }

    /** 依狀態重畫整頁（狀態改變的次數很少：開始、完成）。 */
    private void render() {
        Scene scene = getScene();
        Node focused = scene == null ? null : scene.getFocusOwner();
        // ProgressOverlay 停用內容時焦點會被 JavaFX 移到外框的「返回」或工具列：計算開始時焦點在這一頁（或外框，
        // 開頁時外殼把焦點放在內容裡）就記下來，算完把焦點放回「檢查外洩」
        if (focused == null || focused.getScene() == null || isInside(focused, getParent())) {
            restoreFocus = true;
        }

        List<Node> children = new ArrayList<>();
        children.add(summary());
        HBox actions = new HBox(checkBreach);
        actions.getStyleClass().add("sv-button-row");
        children.add(actions);
        if (model.breach() == Breach.CONNECTION_FAILED) {
            Banner failed = new Banner(Banner.Kind.WARNING, text.getString("breach.failed"));
            failed.setId("hlt-breach-failed");
            children.add(failed);
        }
        if (model.analysis() == Analysis.FAILED) {
            Banner failed = new Banner(Banner.Kind.ERROR, text.getString("error.analyze"));
            failed.setId("hlt-analyze-failed");
            children.add(failed);
        }
        if (model.breach() == Breach.COMPLETED) {
            children.add(breachSection());
        }
        if (model.analysis() == Analysis.READY) {
            if (model.isClean()) {
                EmptyState empty = new EmptyState(IconName.CHECK_CIRCLE, text.getString("empty.title"),
                        text.getString("empty.description"), null, null);
                empty.setId("hlt-empty");
                children.add(empty);
            } else {
                if (!model.weakRows().isEmpty()) {
                    children.add(section("hlt-section-weak", text.getString("section.weak"),
                            List.of(model.weakRows())));
                }
                if (!model.reusedGroups().isEmpty()) {
                    children.add(section("hlt-section-reused", text.getString("section.reused"),
                            model.reusedGroups()));
                }
            }
        }
        body.getChildren().setAll(children);
        checkBreach.setDisable(model.breach() == Breach.RUNNING);

        if (model.analysis() == Analysis.RUNNING) {
            overlay.show(text.getString("progress.analyzing"));
        } else if (model.breach() == Breach.RUNNING) {
            showBreachProgress(0, 0);
        } else {
            overlay.hide();
            if (restoreFocus && scene != null && model.analysis() != Analysis.IDLE) {
                restoreFocus = false;
                checkBreach.requestFocus();
            }
        }
    }

    private VBox summary() {
        String weak = model.analysis() == Analysis.READY ? count(model.weakRows().size()) : "";
        String reused = model.analysis() == Analysis.READY ? count(model.reusedCount()) : "";
        String breached = switch (model.breach()) {
            case NOT_CHECKED, RUNNING -> text.getString("summary.notChecked");
            case COMPLETED -> count(model.breachRows().size());
            case CONNECTION_FAILED -> text.getString("summary.connectionFailed");
        };
        return section("hlt-summary", text.getString("summary.title"), List.of(List.of()),
                summaryRow("hlt-summary-weak", "summary.weak", weak),
                summaryRow("hlt-summary-reused", "summary.reused", reused),
                summaryRow("hlt-summary-breached", "summary.breached", breached));
    }

    private SettingsRow summaryRow(String id, String key, String value) {
        Label v = new Label(value);
        v.setId(id);
        return new SettingsRow(text.getString(key), text.getString(key + ".description"), v);
    }

    private VBox breachSection() {
        if (model.breachRows().isEmpty()) {
            SettingsRow row = new SettingsRow(text.getString("breach.clean"), null, Icons.of(IconName.CHECK_CIRCLE));
            row.setId("hlt-breach-clean");
            return section("hlt-section-breached", text.getString("section.breached"), List.of(List.of()), row);
        }
        return section("hlt-section-breached", text.getString("section.breached"), List.of(model.breachRows()));
    }

    /**
     * 區塊標題＋一或多張卡片（重複使用的密碼一組一張，看得出哪些條目共用同一個密碼）。
     *
     * @param extra 不是條目的列（摘要、沒有外洩）；有時放在第一張卡片
     */
    private VBox section(String id, String title, List<List<Row>> cards, SettingsRow... extra) {
        Label heading = new Label(title);
        heading.getStyleClass().add("sv-section-title");
        heading.setWrapText(true);
        VBox box = new VBox(heading);
        box.setId(id);
        box.getStyleClass().add("sv-settings-section");
        int index = 0;
        for (List<Row> rows : cards) {
            List<Node> nodes = new ArrayList<>(List.of(extra));
            for (Row r : rows) {
                nodes.add(entryRow(id + "-" + index++, r));
            }
            if (nodes.isEmpty()) {
                continue;
            }
            nodes.get(nodes.size() - 1).getStyleClass().add("sv-settings-row-last");
            VBox card = new VBox();
            card.getChildren().setAll(nodes);
            card.getStyleClass().addAll("sv-card", "sv-settings-card");
            box.getChildren().add(card);
            extra = new SettingsRow[0];
        }
        return box;
    }

    private SettingsRow entryRow(String id, Row r) {
        UUID entryId = r.entryId();
        // 名稱與原因的文字在這裡組（模型在 core，T-117）
        String title = r.title().isEmpty() ? text.getString("row.untitled") : r.title();
        String reason = switch (r.reason()) {
            case WEAK -> text.getString("reason.weak");
            case REUSED -> MessageFormat.format(text.getString("reason.reused"), r.count());
            case BREACHED -> MessageFormat.format(text.getString("reason.breached"), r.count());
        };
        IconButton open = new IconButton(IconName.CHEVRON_RIGHT,
                MessageFormat.format(text.getString("row.open"), title));
        open.setId(id);
        open.setOnAction(e -> openEntry(entryId));
        SettingsRow row = new SettingsRow(title, reason, open);
        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                openEntry(entryId);
            }
        });
        return row;
    }

    private void openEntry(UUID entryId) {
        guard.run(HealthDesktopFeature.ID, "openEntry", () -> navigator.openEntry(entryId));
    }

    private static boolean isInside(Node node, Node ancestor) {
        if (ancestor == null) {
            return false;
        }
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private String count(int n) {
        return MessageFormat.format(text.getString("summary.count"), n);
    }
}
