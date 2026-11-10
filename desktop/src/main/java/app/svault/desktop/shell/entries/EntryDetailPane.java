package app.svault.desktop.shell.entries;

import app.svault.core.shell.Texts;
import app.svault.core.vault.EntryView;
import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.FieldRow;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.Icons;
import app.svault.desktop.ui.PageHeader;
import app.svault.desktop.ui.SvButton;
import java.text.MessageFormat;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ResourceBundle;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;

/**
 * 右欄的條目詳細：帳號、密碼（遮罩＋顯示＋複製，AC-02-5）、網址（開啟＋複製）、備註；
 * 「編輯」「刪除」。資源回收桶裡的條目改為「還原」「永久刪除」。
 */
final class EntryDetailPane extends VBox {

    /** 詳細頁的動作。 */
    interface Actions {
        /** @param entry 編輯（{@code Ctrl+E}） */
        void edit(EntryView entry);

        /** @param entry 刪除到資源回收桶（先確認，AC-02-3） */
        void delete(EntryView entry);

        /** @param entry 從資源回收桶還原 */
        void restore(EntryView entry);

        /** @param entry 永久刪除（先確認） */
        void deletePermanently(EntryView entry);

        /**
         * @param entry 條目
         * @param kind  複製哪個欄位
         * @param row   欄位列（顯示「已複製」）
         */
        void copy(EntryView entry, FieldRow.Kind kind, FieldRow row);

        /** @param entry 用系統瀏覽器開啟網址（{@code Ctrl+U}） */
        void openUrl(EntryView entry);
    }

    private static final DateTimeFormatter TIME = Texts.MINUTES;

    private final ResourceBundle text;
    private final Actions actions;
    private final DateTimeFormatter time;
    private EntryView entry;
    private FieldRow password;
    private SvButton primary;
    private SvButton secondary;

    /**
     * @param text    畫面文字
     * @param actions 動作
     * @param zone    修改時間的顯示時區（裝置時區）
     */
    EntryDetailPane(ResourceBundle text, Actions actions, ZoneId zone) {
        this.text = text;
        this.actions = actions;
        this.time = TIME.withZone(zone);
        getStyleClass().add("sv-detail-pane");
        showNone();
    }

    /** 沒有選取條目。 */
    void showNone() {
        entry = null;
        password = null;
        primary = null;
        secondary = null;
        EmptyState none = new EmptyState(IconName.KEY, text.getString("detail.none.title"),
                text.getString("detail.none.description"), null, null);
        none.setId("detail-none");
        getChildren().setAll(none);
    }

    /**
     * @param shown 要顯示的條目
     * @param inBin 是否在資源回收桶裡
     */
    void show(EntryView shown, boolean inBin) {
        entry = shown;
        if (inBin) {
            primary = SvButton.primary(text.getString("detail.restore"));
            primary.setOnAction(e -> actions.restore(entry));
            primary.setId("restore-entry");
            secondary = SvButton.danger(text.getString("detail.deletePermanently"));
            secondary.setOnAction(e -> actions.deletePermanently(entry));
            secondary.setId("delete-permanently");
        } else {
            primary = SvButton.primary(text.getString("detail.edit"));
            primary.setGraphic(Icons.of(IconName.EDIT));
            primary.setOnAction(e -> actions.edit(entry));
            primary.setId("edit-entry");
            secondary = SvButton.ghost(text.getString("detail.delete"));
            secondary.setGraphic(Icons.of(IconName.DELETE));
            secondary.setOnAction(e -> actions.delete(entry));
            secondary.setId("delete-entry");
        }
        PageHeader header = new PageHeader(shown.title(), secondary, primary);
        // 長名稱換行，不把按鈕擠出畫面
        header.setId("detail-header");

        VBox fields = new VBox();
        fields.getStyleClass().add("sv-card");
        fields.getChildren().add(row(FieldRow.Kind.TEXT, "detail.username", shown.username()));
        password = row(FieldRow.Kind.PASSWORD, "detail.password", shown.password());
        password.setId("detail-password");
        fields.getChildren().add(password);
        if (!shown.url().isEmpty()) {
            fields.getChildren().add(row(FieldRow.Kind.URL, "detail.url", shown.url()));
        }
        if (!shown.notes().isEmpty()) {
            fields.getChildren().add(row(FieldRow.Kind.NOTES, "detail.notes", shown.notes()));
        }

        VBox body = new VBox();
        body.getStyleClass().add("sv-detail-body");
        if (inBin) {
            Label note = new Label(text.getString("detail.inBin"));
            note.getStyleClass().add("sv-caption");
            body.getChildren().add(note);
        }
        body.getChildren().add(fields);
        Label modified = caption(MessageFormat.format(text.getString("detail.modified"),
                time.format(shown.lastModified())));
        modified.setId("detail-modified");
        body.getChildren().add(modified);
        int extras = shown.customFields().size() + shown.attachmentNames().size();
        if (extras > 0) {
            body.getChildren().add(caption(MessageFormat.format(text.getString("detail.extras"), extras)));
        }
        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sv-detail-scroll");
        VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);
        getChildren().setAll(header, scroll);
    }

    /** @return 顯示中的條目；沒有時 {@code null} */
    EntryView entry() {
        return entry;
    }

    /** {@code Alt+V}：顯示／隱藏密碼明文（AC-02-5）。 */
    void toggleReveal() {
        if (password != null) {
            password.setRevealed(!password.isRevealed());
        }
    }

    /** @param busy 存檔中：不能編輯、刪除（DESIGN §6） */
    void setBusy(boolean busy) {
        if (primary != null) {
            primary.setDisable(busy);
            secondary.setDisable(busy);
        }
    }

    /** 焦點移到第一個行動（清單上按 Enter 開啟）。 */
    void focusFirst() {
        if (primary != null) {
            primary.requestFocus();
        }
    }

    private FieldRow row(FieldRow.Kind kind, String labelKey, String value) {
        FieldRow[] holder = new FieldRow[1];
        holder[0] = new FieldRow(kind, text.getString(labelKey), () -> value,
                () -> actions.copy(entry, kind, holder[0]),
                kind == FieldRow.Kind.URL ? () -> actions.openUrl(entry) : null);
        return holder[0];
    }

    private static Label caption(String s) {
        Label l = new Label(s);
        l.getStyleClass().add("sv-caption");
        l.setWrapText(true);
        return l;
    }
}
