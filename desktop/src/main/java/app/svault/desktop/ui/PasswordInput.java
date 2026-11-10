package app.svault.desktop.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * 密碼欄（PasswordField、AC-02-5）：遮罩／明文切換（按鈕或 {@code Alt+V}），明文用等寬字；
 * 可附強度條。
 *
 * <p>JavaFX 的文字欄位內部用 {@code String} 存放內容，無法清零（Architecture §10 的記憶體限制）；
 * 取值用 {@link #password()} 拿到 {@code char[]}，呼叫端用完自行清零，{@link #clear()} 清掉欄位內容。
 */
public final class PasswordInput extends VBox {

    private static final KeyCombination TOGGLE = new KeyCodeCombination(KeyCode.V, KeyCombination.ALT_DOWN);

    private final PasswordField masked = new PasswordField();
    private final TextField plain = new TextField();
    private final IconButton toggle;
    private final StrengthMeter meter;
    private final BooleanProperty revealed = new SimpleBooleanProperty(this, "revealed", false);

    /**
     * @param withMeter 是否顯示強度條（建立保管庫、編輯條目）
     */
    public PasswordInput(boolean withMeter) {
        getStyleClass().add("sv-password-input");
        plain.getStyleClass().add("sv-mono");
        plain.textProperty().bindBidirectional(masked.textProperty());
        toggle = new IconButton(IconName.VISIBILITY, UiText.get("password.show"));
        toggle.setOnAction(e -> setRevealed(!isRevealed()));
        revealed.addListener((obs, old, now) -> applyRevealed(now));

        StackPane fields = new StackPane(masked, plain);
        HBox.setHgrow(fields, Priority.ALWAYS);
        HBox row = new HBox(fields, toggle);
        row.getStyleClass().add("sv-password-row");
        getChildren().add(row);
        meter = withMeter ? new StrengthMeter() : null;
        if (meter != null) {
            getChildren().add(meter);
        }
        addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (TOGGLE.match(e)) {
                setRevealed(!isRevealed());
                e.consume();
            }
        });
        applyRevealed(false);
    }

    /** @return 遮罩用的欄位（給 {@link FormField} 當作標籤對象與錯誤樣式的承載者） */
    public PasswordField maskedField() {
        return masked;
    }

    /** @return 明文欄位 */
    public TextField plainField() {
        return plain;
    }

    /** @return 強度條；沒有時 {@code null} */
    public StrengthMeter meter() {
        return meter;
    }

    /** @return 是否顯示明文 */
    public BooleanProperty revealedProperty() {
        return revealed;
    }

    /** @return 是否顯示明文 */
    public boolean isRevealed() {
        return revealed.get();
    }

    /** @param value 是否顯示明文 */
    public void setRevealed(boolean value) {
        revealed.set(value);
    }

    /**
     * @return 目前內容的複本；呼叫端用完要以 {@code Arrays.fill(chars, '\0')} 清零
     */
    public char[] password() {
        return masked.getText().toCharArray();
    }

    /** 清掉欄位內容並回到遮罩狀態。 */
    public void clear() {
        masked.clear();
        setRevealed(false);
    }

    private void applyRevealed(boolean show) {
        boolean hadFocus = masked.isFocused() || plain.isFocused();
        TextField from = show ? masked : plain;
        TextField to = show ? plain : masked;
        int caret = from.getCaretPosition();
        to.setVisible(true);
        to.setManaged(true);
        from.setVisible(false);
        from.setManaged(false);
        toggle.setIcon(show ? IconName.VISIBILITY_OFF : IconName.VISIBILITY,
                UiText.get(show ? "password.hide" : "password.show"));
        if (hadFocus) {
            to.requestFocus();
            to.positionCaret(caret);
        }
    }
}
