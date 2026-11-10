package app.svault.desktop.ui;

import java.util.function.Supplier;
import javafx.animation.PauseTransition;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * 詳細頁的一個欄位（FieldRow）：標籤＋值＋動作，每個動作是獨立的 IconButton。
 * 密碼預設遮罩，固定顯示 8 個點（不透露長度），明文只在使用者要求時才向 {@code reveal} 取值。
 */
public final class FieldRow extends HBox {

    /** 欄位種類。 */
    public enum Kind { TEXT, PASSWORD, URL, NOTES }

    private static final String MASK = "••••••••";
    private static final Duration COPIED_FOR = Duration.seconds(2);

    private static final PseudoClass COPIED = PseudoClass.getPseudoClass("copied");

    private final Kind kind;
    private final String name;
    private final Label value = new Label();
    private final HBox actions = new HBox();
    private final IconButton copy;
    private final Supplier<String> reveal;
    private IconButton revealButton;
    private boolean revealed;

    /**
     * @param kind      種類
     * @param labelText 欄位名稱
     * @param reveal    取值（密碼欄位只在顯示明文時呼叫）
     * @param onCopy    複製動作
     * @param onOpen    開啟網址（只有 {@link Kind#URL} 使用，其他傳 {@code null}）
     */
    public FieldRow(Kind kind, String labelText, Supplier<String> reveal, Runnable onCopy, Runnable onOpen) {
        this.kind = kind;
        this.name = labelText;
        this.reveal = reveal;
        getStyleClass().add("sv-field-row");
        Label label = new Label(labelText);
        label.getStyleClass().add("sv-field-row-label");
        value.getStyleClass().add("sv-field-row-value");
        value.setWrapText(kind == Kind.NOTES);
        VBox texts = new VBox(label, value);
        texts.getStyleClass().add("sv-field-row-texts");
        HBox.setHgrow(texts, Priority.ALWAYS);
        actions.getStyleClass().add("sv-field-row-actions");

        if (kind == Kind.PASSWORD) {
            value.getStyleClass().add("sv-mono");
            revealButton = new IconButton(IconName.VISIBILITY, UiText.format("field.show", labelText));
            revealButton.setOnAction(e -> setRevealed(!revealed));
            actions.getChildren().add(revealButton);
        }
        if (kind == Kind.URL && onOpen != null) {
            IconButton open = new IconButton(IconName.OPEN_IN_NEW, UiText.format("field.open", labelText));
            open.setOnAction(e -> onOpen.run());
            actions.getChildren().add(open);
        }
        copy = new IconButton(IconName.CONTENT_COPY, UiText.format("field.copy", labelText));
        copy.setOnAction(e -> onCopy.run());
        actions.getChildren().add(copy);
        getChildren().addAll(texts, actions);
        refreshValue();
    }

    /**
     * @param value 是否顯示密碼明文（只對 {@link Kind#PASSWORD} 有效）
     */
    public void setRevealed(boolean value) {
        if (kind != Kind.PASSWORD) {
            return;
        }
        revealed = value;
        revealButton.setIcon(value ? IconName.VISIBILITY_OFF : IconName.VISIBILITY,
                UiText.format(value ? "field.hide" : "field.show", name));
        refreshValue();
    }

    /** @return 是否顯示密碼明文 */
    public boolean isRevealed() {
        return revealed;
    }

    /** 顯示「已複製」：複製鈕暫時換成勾勾（2 秒），螢幕閱讀器讀到「已複製」。 */
    public void markCopied() {
        String normal = UiText.format("field.copy", name);
        copy.setIcon(IconName.CHECK_CIRCLE, UiText.get("field.copied"));
        copy.pseudoClassStateChanged(COPIED, true);
        PauseTransition back = new PauseTransition(COPIED_FOR);
        back.setOnFinished(e -> {
            copy.setIcon(IconName.CONTENT_COPY, normal);
            copy.pseudoClassStateChanged(COPIED, false);
        });
        back.play();
    }

    /** 值改變時重新取值（例如編輯後回到詳細頁）。 */
    public final void refreshValue() {
        if (kind == Kind.PASSWORD && !revealed) {
            value.setText(MASK);
            value.setAccessibleText(UiText.get("field.masked"));
        } else {
            String v = reveal.get();
            value.setText(v == null ? "" : v);
            value.setAccessibleText(null);
        }
    }

}
