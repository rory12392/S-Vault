package app.svault.desktop.shell.entries;

import app.svault.core.security.PasswordStrength;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.desktop.ui.FormField;
import app.svault.desktop.ui.PageHeader;
import app.svault.desktop.ui.PasswordInput;
import app.svault.desktop.ui.StrengthMeter;
import app.svault.desktop.ui.SvButton;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * 右欄的條目編輯與新增：名稱（必填）、帳號、密碼（遮罩＋強度條，旁邊是功能模組的
 * {@code PasswordFieldAction}，例如 GEN 的「產生」）、網址、備註、群組。「儲存」（{@code Ctrl+S}、Enter）、
 * 「取消」（{@code Esc}）。
 */
final class EntryEditPane extends VBox {

    /** 編輯畫面的動作。 */
    interface Actions {
        /** 儲存（先驗證）。 */
        void save();

        /** 取消（有修改時先確認「捨棄修改？」）。 */
        void cancel();
    }

    /**
     * 密碼欄旁的按鈕。外殼已把功能模組的動作包在 {@code ModuleGuard} 裡：出錯或不填入時回傳空。
     *
     * @param label  按鈕文字
     * @param action 回傳要填入的密碼；呼叫端填入後清零
     */
    record PasswordButton(String label, Supplier<Optional<char[]>> action) { }

    private final ResourceBundle text;
    private final Function<CharSequence, PasswordStrength> estimator;
    private final FormField name;
    private final FormField username;
    private final PasswordInput password = new PasswordInput(true);
    private final FormField url;
    private final TextArea notes = new TextArea();
    private final ComboBox<GroupView> group = new ComboBox<>();
    private final SvButton save;
    private final SvButton cancel;
    private final PageHeader header;
    private EntryView editing;
    private EntryDraft initial;
    private UUID initialGroup;

    /**
     * @param text      畫面文字
     * @param estimator 密碼強度（{@code StrengthEstimator::estimate}）
     * @param buttons   密碼欄旁的按鈕（沒有功能模組時是空的）
     * @param pathOf    群組在下拉選單中顯示的名稱（群組路徑）
     * @param actions   動作
     */
    EntryEditPane(ResourceBundle text, Function<CharSequence, PasswordStrength> estimator,
            List<PasswordButton> buttons, Function<GroupView, String> pathOf, Actions actions) {
        this.text = text;
        this.estimator = estimator;
        getStyleClass().add("sv-detail-pane");
        name = FormField.text(text.getString("edit.name"));
        name.textField().setId("edit-name");
        name.textField().textProperty().addListener((obs, old, now) -> name.setError(null));
        username = FormField.text(text.getString("edit.username"));
        username.textField().setId("edit-username");
        url = FormField.text(text.getString("edit.url"));
        url.textField().setId("edit-url");
        password.maskedField().setId("edit-password");
        password.plainField().setId("edit-password-plain");
        password.maskedField().textProperty().addListener((obs, old, now) -> updateStrength(now));

        HBox passwordRow = new HBox(password);
        passwordRow.getStyleClass().add("sv-password-actions");
        HBox.setHgrow(password, Priority.ALWAYS);
        for (PasswordButton b : buttons) {
            SvButton button = SvButton.secondary(b.label());
            button.setOnAction(e -> b.action().get().ifPresent(this::fillPassword));
            passwordRow.getChildren().add(button);
        }
        FormField passwordField = new FormField(text.getString("edit.password"), password.maskedField(), passwordRow);

        notes.setId("edit-notes");
        notes.setWrapText(true);
        notes.setPrefRowCount(4);
        FormField notesField = new FormField(text.getString("edit.notes"), notes, notes);

        group.setId("edit-group");
        group.setMaxWidth(Double.MAX_VALUE);
        group.setConverter(new StringConverter<>() {
            @Override
            public String toString(GroupView g) {
                return g == null ? "" : pathOf.apply(g);
            }

            @Override
            public GroupView fromString(String s) {
                return null;
            }
        });
        FormField groupField = new FormField(text.getString("edit.group"), group, group);

        cancel = SvButton.secondary(text.getString("edit.cancel"));
        cancel.setId("edit-cancel");
        cancel.setOnAction(e -> actions.cancel());
        save = SvButton.primary(text.getString("edit.save"));
        save.setId("edit-save");
        save.setDefaultButton(true);
        save.setOnAction(e -> actions.save());
        header = new PageHeader("", cancel, save);

        VBox form = new VBox(name, username, passwordField, url, notesField, groupField);
        form.getStyleClass().add("sv-form");
        ScrollPane scroll = new ScrollPane(form);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sv-detail-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(header, scroll);
    }

    /**
     * 開始編輯或新增。
     *
     * @param entry   要編輯的條目；新增時 {@code null}
     * @param groups  可以選的群組
     * @param groupId 預設群組
     */
    void start(EntryView entry, List<GroupView> groups, UUID groupId) {
        editing = entry;
        header.setTitle(text.getString(entry == null ? "edit.titleNew" : "edit.titleEdit"));
        initial = entry == null ? new EntryDraft("", "", "", "", "") : EntryDraft.of(entry);
        initialGroup = groupId;
        name.textField().setText(initial.title());
        username.textField().setText(initial.username());
        password.clear();
        password.maskedField().setText(initial.password());
        url.textField().setText(initial.url());
        notes.setText(initial.notes());
        group.getItems().setAll(groups);
        group.getSelectionModel().select(groups.stream().filter(g -> g.uuid().equals(groupId)).findFirst()
                .orElse(groups.isEmpty() ? null : groups.get(0)));
        name.setError(null);
        setSaving(false);
    }

    /** @return 編輯中的條目；新增時 {@code null} */
    EntryView editing() {
        return editing;
    }

    /** @return 編輯開始時的群組 */
    UUID initialGroup() {
        return initialGroup;
    }

    /** @return 目前欄位的內容 */
    EntryDraft draft() {
        return new EntryDraft(name.textField().getText(), username.textField().getText(),
                password.maskedField().getText(), url.textField().getText(), notes.getText());
    }

    /** @return 選定的群組 */
    UUID group() {
        GroupView g = group.getValue();
        return g == null ? initialGroup : g.uuid();
    }

    /** @return 和開始時不同（離開前要確認「捨棄修改？」） */
    boolean dirty() {
        return initial != null && (!draft().equals(initial) || !group().equals(initialGroup));
    }

    /**
     * 驗證（名稱必填）；失敗時錯誤顯示在欄位下方，焦點移到第一個錯誤欄位（DESIGN §6）。
     *
     * @return 沒有錯誤
     */
    boolean validate() {
        if (draft().title().strip().isEmpty()) {
            name.setError(text.getString("edit.error.name"));
            name.textField().requestFocus();
            return false;
        }
        return true;
    }

    /** @param saving 儲存中：「儲存」按鈕 loading */
    void setSaving(boolean saving) {
        save.setLoading(saving);
        // 儲存中不能離開：結果回來時要回到詳細並選到這筆條目
        cancel.setDisable(saving);
    }

    /** 焦點移到名稱欄。 */
    void focusDefault() {
        TextField f = name.textField();
        f.requestFocus();
        f.end();
    }

    /** 離開編輯：清掉欄位內容（密碼欄的字串無法清零，至少不留在畫面上）。 */
    void clear() {
        password.clear();
        name.textField().clear();
        username.textField().clear();
        url.textField().clear();
        notes.clear();
        editing = null;
        initial = null;
    }

    private void fillPassword(char[] value) {
        try {
            password.maskedField().setText(new String(value));
        } finally {
            Arrays.fill(value, '\0');
        }
    }

    private void updateStrength(String value) {
        StrengthMeter meter = password.meter();
        if (value == null || value.isEmpty()) {
            meter.setStrength(StrengthMeter.Strength.NONE);
            return;
        }
        meter.setStrength(switch (estimator.apply(value)) {
            case WEAK -> StrengthMeter.Strength.WEAK;
            case MEDIUM -> StrengthMeter.Strength.MEDIUM;
            case STRONG -> StrengthMeter.Strength.STRONG;
        });
    }
}
