package app.svault.desktop.shell.start;

import app.svault.core.security.PasswordStrength;
import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.FormField;
import app.svault.desktop.ui.PasswordInput;
import app.svault.desktop.ui.StrengthMeter;
import app.svault.desktop.ui.SvButton;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Function;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 建立保管庫（US-01）：名稱、存放位置、主密碼＋強度、再次輸入、了解後果的核取方塊。
 * 「建立保管庫」在勾選前仍可按，按下時才顯示驗證錯誤（不用 disabled）；強度為弱只提示、不阻擋。
 */
final class CreateScreen extends VBox {

    /** 畫面上的動作（由 {@link StartFlow} 實作）。 */
    interface Actions {
        void chooseLocation();

        void submit();

        void back();
    }

    private final ResourceBundle text;
    private final Function<CharSequence, PasswordStrength> estimator;
    private final VBox problemSlot = new VBox();
    private final FormField name;
    private final Label locationText = new Label();
    private final SvButton chooseButton;
    private final FormField location;
    private final PasswordInput password = new PasswordInput(true);
    private final FormField passwordField;
    private final Label weak = new Label();
    private final PasswordInput confirm = new PasswordInput(false);
    private final FormField confirmField;
    private final CheckBox acknowledge;
    private final Label acknowledgeError = new Label();
    private final SvButton submit;
    private final SvButton back;
    private final Label busyText = new Label();
    private final Map<CreateForm.Field, Control> focusTargets = new EnumMap<>(CreateForm.Field.class);
    private Path file;

    /**
     * @param text      文字
     * @param estimator 主密碼強度（{@code StrengthEstimator::estimate}）
     * @param actions   畫面上的動作
     */
    CreateScreen(ResourceBundle text, Function<CharSequence, PasswordStrength> estimator, Actions actions) {
        this.text = text;
        this.estimator = estimator;
        getStyleClass().addAll("sv-form", "sv-form-compact");

        Label title = new Label(text.getString("create.title"));
        title.getStyleClass().add("sv-page-title");
        // 沒有訊息時不佔位置（否則表單多一段間距）
        problemSlot.managedProperty().bind(Bindings.isNotEmpty(problemSlot.getChildren()));

        name = FormField.text(text.getString("create.name"));
        name.textField().setId("create-name");
        name.textField().setText(text.getString("create.name.default"));

        chooseButton = SvButton.secondary(text.getString("create.location.choose"));
        chooseButton.setId("create-location");
        chooseButton.setAccessibleText(text.getString("create.location.accessible"));
        chooseButton.setOnAction(e -> actions.chooseLocation());
        // 路徑很長時省略路徑，按鈕文字不截斷
        chooseButton.setMinWidth(Region.USE_PREF_SIZE);
        locationText.getStyleClass().add("sv-caption");
        locationText.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);
        locationText.setMinWidth(0);
        locationText.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(locationText, Priority.ALWAYS);
        HBox locationRow = new HBox(locationText, chooseButton);
        locationRow.getStyleClass().add("sv-location-row");
        location = new FormField(text.getString("create.location"), chooseButton, locationRow);
        setFile(null);

        password.maskedField().setId("create-password");
        passwordField = FormField.password(text.getString("create.password"), password);
        weak.setText(text.getString("create.weak"));
        weak.getStyleClass().add("sv-field-warning");
        weak.setWrapText(true);
        showWeak(false);
        password.maskedField().textProperty().addListener((obs, old, now) -> updateStrength(now));

        confirm.maskedField().setId("create-confirm");
        confirmField = FormField.password(text.getString("create.confirm"), confirm);

        acknowledge = new CheckBox(text.getString("create.acknowledge"));
        acknowledge.setId("create-acknowledge");
        acknowledge.setWrapText(true);
        acknowledgeError.getStyleClass().add("sv-field-error");
        acknowledgeError.setWrapText(true);
        showAcknowledgeError(null);
        VBox acknowledgeBox = new VBox(acknowledge, acknowledgeError);
        acknowledgeBox.getStyleClass().add("sv-form-field");

        submit = SvButton.primary(text.getString("create.submit"));
        submit.setId("create-submit");
        submit.setDefaultButton(true);
        submit.setMaxWidth(Double.MAX_VALUE);
        submit.setOnAction(e -> actions.submit());
        busyText.setText(text.getString("create.creating"));
        busyText.getStyleClass().add("sv-caption");
        busyText.setVisible(false);
        busyText.setManaged(false);
        back = SvButton.ghost(text.getString("create.back"));
        back.setId("create-back");
        // Esc 回到歡迎畫面（Esc 取消）
        back.setCancelButton(true);
        back.setOnAction(e -> actions.back());

        focusTargets.put(CreateForm.Field.NAME, name.input());
        focusTargets.put(CreateForm.Field.LOCATION, chooseButton);
        focusTargets.put(CreateForm.Field.PASSWORD, password.maskedField());
        focusTargets.put(CreateForm.Field.CONFIRM, confirm.maskedField());
        focusTargets.put(CreateForm.Field.ACKNOWLEDGE, acknowledge);

        // 改了欄位就收掉那一欄的錯誤，避免修正後還看到舊的訊息
        name.textField().textProperty().addListener((obs, old, now) -> name.setError(null));
        password.maskedField().textProperty().addListener((obs, old, now) -> passwordField.setError(null));
        confirm.maskedField().textProperty().addListener((obs, old, now) -> confirmField.setError(null));
        acknowledge.selectedProperty().addListener((obs, old, now) -> showAcknowledgeError(null));

        VBox passwordBox = new VBox(passwordField, weak);
        passwordBox.getStyleClass().add("sv-form-field");
        getChildren().addAll(title, problemSlot, name, location, passwordBox, confirmField, acknowledgeBox,
                submit, busyText, back);
    }

    /** @return 保管庫名稱 */
    String vaultName() {
        return name.textField().getText();
    }

    /** @return 選好的存放位置；還沒選時 {@code null} */
    Path file() {
        return file;
    }

    /** @param value 存放位置；{@code null} 表示還沒選 */
    void setFile(Path value) {
        file = value;
        locationText.setText(value == null ? text.getString("create.location.none") : value.toString());
        location.setError(null);
    }

    /**
     * @return 目前表單內容；主密碼是新的陣列，呼叫端用完清零（{@link Secrets#wipe(char[])}）
     */
    CreateForm form() {
        return new CreateForm(vaultName(), file, password.password(), confirm.password(), acknowledge.isSelected());
    }

    /**
     * 顯示驗證錯誤，焦點移到第一個錯誤欄位。
     *
     * @param problems {@link CreateForm#validate()} 的結果
     */
    void showProblems(List<CreateForm.Problem> problems) {
        clearProblems();
        for (CreateForm.Problem p : problems) {
            String message = text.getString(p.messageKey());
            switch (p.field()) {
                case NAME -> name.setError(message);
                case LOCATION -> location.setError(message);
                case PASSWORD -> passwordField.setError(message);
                case CONFIRM -> confirmField.setError(message);
                case ACKNOWLEDGE -> showAcknowledgeError(message);
            }
        }
        if (!problems.isEmpty()) {
            focusTargets.get(problems.get(0).field()).requestFocus();
        }
    }

    /** @param messageKey 建立失敗的 error Banner 文字鍵；{@code null} 清除 */
    void showFailure(String messageKey) {
        problemSlot.getChildren().clear();
        if (messageKey != null) {
            Banner banner = new Banner(Banner.Kind.ERROR, text.getString(messageKey));
            banner.setId("create-problem");
            problemSlot.getChildren().add(banner);
        }
    }

    /** 清掉所有錯誤訊息。 */
    void clearProblems() {
        name.setError(null);
        location.setError(null);
        passwordField.setError(null);
        confirmField.setError(null);
        showAcknowledgeError(null);
        showFailure(null);
    }

    /**
     * 建立中：按鈕 loading＋「正在建立…」，其他欄位停用。
     *
     * @param busy 是否建立中
     */
    void setBusy(boolean busy) {
        submit.setLoading(busy);
        busyText.setVisible(busy);
        busyText.setManaged(busy);
        for (Node n : List.of(name, location, passwordField, confirmField, acknowledge, back)) {
            n.setDisable(busy);
        }
    }

    /** 建立完成或離開畫面時清掉主密碼欄位。 */
    void clearPasswords() {
        password.clear();
        confirm.clear();
    }

    /** 進入畫面時的焦點。 */
    void focusDefault() {
        name.input().requestFocus();
    }

    private void updateStrength(String value) {
        StrengthMeter meter = password.meter();
        if (value == null || value.isEmpty()) {
            meter.setStrength(StrengthMeter.Strength.NONE);
            showWeak(false);
            return;
        }
        PasswordStrength s = estimator.apply(value);
        meter.setStrength(switch (s) {
            case WEAK -> StrengthMeter.Strength.WEAK;
            case MEDIUM -> StrengthMeter.Strength.MEDIUM;
            case STRONG -> StrengthMeter.Strength.STRONG;
        });
        showWeak(s == PasswordStrength.WEAK);
    }

    private void showWeak(boolean visible) {
        weak.setVisible(visible);
        weak.setManaged(visible);
    }

    private void showAcknowledgeError(String message) {
        boolean has = message != null;
        acknowledgeError.setText(has ? message : "");
        acknowledgeError.setVisible(has);
        acknowledgeError.setManaged(has);
        acknowledge.setAccessibleHelp(has ? message : null);
    }
}
