package app.svault.desktop.shell.entries;

import app.svault.core.shell.ConflictChoices;
import app.svault.core.shell.Texts;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import app.svault.desktop.ui.DiffRow;
import app.svault.desktop.ui.SvButton;
import app.svault.desktop.ui.ThemeManager;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * 衝突對照（US-08）：標題「解決衝突（第 n／N 筆）」＋條目名稱、
 * 「全部用這台裝置的」「全部用另一台裝置的」、逐欄 {@link DiffRow}（只列衝突的欄位，AC-08-1）、說明「沒被選的值會保存在
 * 這筆條目的歷史紀錄中。」（AC-08-2）。「上一筆」「下一筆」逐筆處理（AC-08-4），最後一筆是「完成並儲存」；還有欄位沒選
 * 時捲到那一欄並提示「還有欄位沒有選擇」。「稍後處理」或 Esc 關閉不解決（AC-08-3：呼叫端不寫遠端）。
 *
 * <p>只負責顯示與收集選擇；寫回在 {@link ConflictFlow}。鍵盤：Tab 依序到快速按鈕、每一欄、下方按鈕；欄位內方向鍵左右
 * 切換並選取；Enter 是「下一筆」／「完成並儲存」；Alt+V 顯示／隱藏這一筆的密碼與受保護的欄位；Esc 關閉。
 */
final class ConflictDialog {

    private static final double MIN_WIDTH = 800;
    private static final double MIN_HEIGHT = 500;
    private static final double PREF_HEIGHT = 560;

    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final ResourceBundle text;
    private final ConflictChoices choices;
    private final ZoneId zone;
    private final Label heading = new Label();
    private final Label entryName = new Label();
    private final Label missing = new Label();
    private final VBox rows = new VBox();
    private final ScrollPane scroll = new ScrollPane(rows);
    private final SvButton useThis;
    private final SvButton useOther;
    private final ButtonType finishType;
    private final ButtonType previousType;
    private final Button next;
    private final Button previous;
    private final List<DiffRow> shown = new ArrayList<>();
    private int index;

    /**
     * @param owner   觸發的元件（決定擁有者視窗與主題）
     * @param text    文字
     * @param choices 衝突與目前的選擇（可能帶著上一次的選擇）
     * @param zone    修改時間的時區
     */
    ConflictDialog(Node owner, ResourceBundle text, ConflictChoices choices, ZoneId zone) {
        this.text = text;
        this.choices = choices;
        this.zone = zone;
        dialog.initModality(Modality.WINDOW_MODAL);
        if (owner != null && owner.getScene() != null) {
            dialog.initOwner(owner.getScene().getWindow());
            ThemeManager theme = ThemeManager.of(owner.getScene());
            if (theme != null) {
                theme.track(dialog.getDialogPane());
            }
        }
        dialog.setResizable(true);
        heading.getStyleClass().add("sv-page-title");
        heading.setId("conflict-title");
        entryName.getStyleClass().add("sv-section-title");
        entryName.setId("conflict-entry");
        entryName.setWrapText(true);
        useThis = SvButton.secondary(text.getString("conflict.useThis"));
        useThis.setId("conflict-use-this");
        useThis.setOnAction(e -> chooseAll(Side.LOCAL));
        useOther = SvButton.secondary(text.getString("conflict.useOther"));
        useOther.setId("conflict-use-other");
        useOther.setOnAction(e -> chooseAll(Side.REMOTE));
        HBox quick = new HBox(useThis, useOther);
        quick.getStyleClass().add("sv-conflict-quick");
        Label hint = new Label(text.getString("conflict.hint"));
        hint.getStyleClass().add("sv-caption");
        hint.setWrapText(true);
        missing.getStyleClass().add("sv-conflict-missing");
        missing.setId("conflict-missing");
        missing.setText(text.getString("conflict.missing"));
        missing.setVisible(false);
        missing.setManaged(false);
        rows.getStyleClass().add("sv-conflict-rows");
        rows.setAccessibleText(text.getString("conflict.rows"));
        scroll.getStyleClass().add("sv-conflict-scroll");
        scroll.setId("conflict-scroll");
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        // 捲動區不用 Tab 停留，焦點直接到裡面的選項
        scroll.setFocusTraversable(false);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox content = new VBox(heading, entryName, quick, hint, missing, scroll);
        content.getStyleClass().add("sv-conflict-content");

        dialog.getDialogPane().getStyleClass().addAll("sv-dialog", "sv-conflict-dialog");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setMinSize(MIN_WIDTH, MIN_HEIGHT);
        dialog.getDialogPane().setPrefSize(MIN_WIDTH, PREF_HEIGHT);

        ButtonType laterType = new ButtonType(text.getString("conflict.later"), ButtonBar.ButtonData.CANCEL_CLOSE);
        previousType = new ButtonType(text.getString("conflict.previous"), ButtonBar.ButtonData.BACK_PREVIOUS);
        finishType = new ButtonType(text.getString("conflict.next"), ButtonBar.ButtonData.NEXT_FORWARD);
        dialog.getDialogPane().getButtonTypes().addAll(laterType, previousType, finishType);
        Button later = (Button) dialog.getDialogPane().lookupButton(laterType);
        later.setId("conflict-later");
        later.getStyleClass().addAll("sv-button", "sv-button-ghost");
        previous = (Button) dialog.getDialogPane().lookupButton(previousType);
        previous.setId("conflict-previous");
        previous.getStyleClass().addAll("sv-button", "sv-button-secondary");
        next = (Button) dialog.getDialogPane().lookupButton(finishType);
        next.setId("conflict-next");
        next.getStyleClass().addAll("sv-button", "sv-button-primary");
        next.setDefaultButton(true);
        // 最後一筆換成較長的「完成並儲存」：寬度跟著文字，不截斷（ButtonBar 預設讓按鈕等寬，換文字後不會重算）
        next.setMinWidth(Region.USE_PREF_SIZE);
        ButtonBar.setButtonUniformSize(next, false);
        // 上一筆、下一筆只換頁；完成並儲存要全部選完才關閉
        previous.addEventFilter(ActionEvent.ACTION, e -> {
            e.consume();
            show(index - 1);
        });
        next.addEventFilter(ActionEvent.ACTION, e -> {
            if (!advance()) {
                e.consume();
            }
        });
        // Windows 上 Enter 會按下聚焦中的按鈕；在欄位的選項上 Enter 應該是「下一筆」（DESIGN §3：預設按鈕 Enter），
        // 不是把那一側選起來。按下與放開都接住，放開時才不會在下一頁的選項上再觸發一次
        dialog.getDialogPane().addEventFilter(KeyEvent.ANY, e -> {
            if (e.getCode() == KeyCode.ENTER && e.getTarget() instanceof Node n
                    && n.getStyleClass().contains("sv-diff-option")) {
                e.consume();
                if (e.getEventType() == KeyEvent.KEY_PRESSED) {
                    next.fire();
                }
            }
        });
        dialog.getDialogPane().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isAltDown() && e.getCode() == KeyCode.V) {
                e.consume();
                boolean reveal = shown.stream().anyMatch(r -> r.isSecret() && !r.isRevealed());
                shown.forEach(r -> r.setRevealed(reveal));
            }
        });
        int start = Math.max(choices.firstIncomplete(), 0);
        show(start);
        dialog.setOnShown(e -> Platform.runLater(this::focusFirstRow));
    }

    /**
     * 顯示並等待使用者處理。
     *
     * @return 全部選完並按「完成並儲存」時的選擇；「稍後處理」、Esc 或關閉視窗時為空（不寫遠端，AC-08-3）
     */
    Optional<Map<UUID, Map<String, Side>>> showAndWait() {
        Optional<ButtonType> result = dialog.showAndWait();
        return result.isPresent() && result.get() == finishType && choices.complete()
                ? Optional.of(choices.toMap()) : Optional.empty();
    }

    // ---------- 換頁 ----------

    private void show(int target) {
        List<ConflictView.Item> items = choices.view().items();
        index = Math.max(0, Math.min(target, items.size() - 1));
        ConflictView.Item item = items.get(index);
        String title = MessageFormat.format(text.getString("conflict.title"), index + 1, items.size());
        heading.setText(title);
        dialog.setTitle(title);
        entryName.setText(item.title().isEmpty() ? text.getString("conflict.untitled") : item.title());
        setMissing(false);
        shown.clear();
        rows.getChildren().clear();
        for (ConflictView.Row row : item.rows()) {
            DiffRow diff = new DiffRow(fieldName(text, row), choice(text, zone, row.local(), row.localTime()),
                    choice(text, zone, row.remote(), row.remoteTime()), row.secret());
            diff.setId("conflict-row-" + row.key().replaceAll("[^A-Za-z0-9]", "-"));
            Side chosen = choices.get(item.entryId(), row.key());
            if (chosen != null) {
                diff.select(chosen == Side.LOCAL ? DiffRow.Side.THIS_DEVICE : DiffRow.Side.OTHER_DEVICE);
            }
            diff.selectedProperty().addListener((obs, old, now) -> {
                if (now != null) {
                    choices.choose(item.entryId(), row.key(),
                            now == DiffRow.Side.THIS_DEVICE ? Side.LOCAL : Side.REMOTE);
                    refreshMissing();
                }
            });
            shown.add(diff);
            rows.getChildren().add(diff);
        }
        previous.setDisable(index == 0);
        boolean last = index == items.size() - 1;
        next.setText(text.getString(last ? "conflict.finish" : "conflict.next"));
        scroll.setVvalue(0);
        if (dialog.isShowing()) {
            Platform.runLater(this::focusFirstRow);
        }
    }

    /** @return 可以關閉（最後一筆而且全部選完） */
    private boolean advance() {
        if (choices.firstUnchosen(index).isPresent()) {
            showMissing();
            return false;
        }
        int incomplete = choices.firstIncomplete();
        boolean last = index == choices.view().items().size() - 1;
        if (!last) {
            show(index + 1);
            return false;
        }
        if (incomplete >= 0) {
            // 最後一筆選完了，但前面還有沒選完的：回到那一筆
            show(incomplete);
            showMissing();
            return false;
        }
        return true;
    }

    private void chooseAll(Side side) {
        choices.chooseAll(index, side);
        DiffRow.Side ui = side == Side.LOCAL ? DiffRow.Side.THIS_DEVICE : DiffRow.Side.OTHER_DEVICE;
        shown.forEach(r -> r.select(ui));
        refreshMissing();
    }

    // ---------- 還有欄位沒選 ----------

    private void showMissing() {
        setMissing(true);
        ConflictView.Item item = choices.view().items().get(index);
        for (int i = 0; i < item.rows().size(); i++) {
            boolean unchosen = choices.get(item.entryId(), item.rows().get(i).key()) == null;
            shown.get(i).setMissing(unchosen);
        }
        choices.firstUnchosen(index).ifPresent(row -> {
            DiffRow target = shown.get(item.rows().indexOf(row));
            scrollTo(target);
            target.focusChoice();
        });
    }

    private void refreshMissing() {
        if (missing.isVisible() && choices.firstUnchosen(index).isEmpty()) {
            setMissing(false);
        }
    }

    private void setMissing(boolean value) {
        missing.setVisible(value);
        missing.setManaged(value);
        if (!value) {
            shown.forEach(r -> r.setMissing(false));
        }
    }

    private void scrollTo(Node target) {
        scroll.applyCss();
        scroll.layout();
        double contentHeight = rows.getBoundsInLocal().getHeight();
        double viewport = scroll.getViewportBounds().getHeight();
        if (contentHeight > viewport) {
            scroll.setVvalue(Math.min(1, target.getBoundsInParent().getMinY() / (contentHeight - viewport)));
        }
    }

    private void focusFirstRow() {
        if (!shown.isEmpty()) {
            shown.get(0).focusChoice();
        }
    }

    // ---------- 文字 ----------

    /**
     * @param text 字串
     * @param row  衝突的欄位
     * @return 欄位名稱（「密碼」「自訂欄位「PIN」」）
     */
    static String fieldName(ResourceBundle text, ConflictView.Row row) {
        return MessageFormat.format(text.getString("conflict.field." + row.kind().name()),
                row.name() == null ? "" : row.name());
    }

    /**
     * @param text  字串
     * @param zone  時區
     * @param value 一邊的值
     * @param time  那一邊的修改時間
     * @return 選項要顯示的值與「修改於 …」；只有值本身（{@code TEXT}）在要遮罩的欄位裡遮罩
     */
    static DiffRow.Choice choice(ResourceBundle text, ZoneId zone, ConflictView.Shown value, Instant time) {
        String arg = value.form() == ConflictView.Shown.Form.EXPIRES
                ? Texts.MINUTES.format(value.time().atZone(zone)) : value.text();
        String shownText = MessageFormat.format(text.getString("conflict.value." + value.form().name()),
                arg == null ? "" : arg);
        String timeText = time == null ? null
                : MessageFormat.format(text.getString("conflict.time"), Texts.MINUTES.format(time.atZone(zone)));
        // 只有值本身要遮罩；「（空白）」「（已刪除）」等說法照常顯示，遮罩時也看得出一邊刪掉了
        return new DiffRow.Choice(shownText, timeText, value.form() == ConflictView.Shown.Form.TEXT);
    }
}
