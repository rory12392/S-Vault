package app.svault.desktop.ui;

import java.util.Locale;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;

/**
 * Button：變體只用 style class（{@code sv-button-primary} 等），外部不覆寫顏色。
 * 一個畫面最多一個 primary。
 */
public final class SvButton extends Button {

    /** 按鈕變體。 */
    public enum Variant {
        PRIMARY, SECONDARY, GHOST, DANGER;

        String styleClass() {
            return "sv-button-" + name().toLowerCase(Locale.ROOT);
        }
    }

    private static final PseudoClass LOADING = PseudoClass.getPseudoClass("loading");

    private boolean loading;
    private boolean disabledBeforeLoading;
    private Node graphicBeforeLoading;

    /**
     * @param text 按鈕文字（動詞）
     * @param variant 變體
     */
    public SvButton(String text, Variant variant) {
        super(text);
        getStyleClass().addAll("sv-button", variant.styleClass());
    }

    /** @param text 按鈕文字 @return primary 按鈕 */
    public static SvButton primary(String text) {
        return new SvButton(text, Variant.PRIMARY);
    }

    /** @param text 按鈕文字 @return secondary 按鈕 */
    public static SvButton secondary(String text) {
        return new SvButton(text, Variant.SECONDARY);
    }

    /** @param text 按鈕文字 @return ghost 按鈕 */
    public static SvButton ghost(String text) {
        return new SvButton(text, Variant.GHOST);
    }

    /** @param text 按鈕文字 @return danger 按鈕 */
    public static SvButton danger(String text) {
        return new SvButton(text, Variant.DANGER);
    }

    /**
     * 送出中：按鈕內顯示 Spinner 並停用；結束後恢復原本的圖示與停用狀態。
     *
     * @param value 是否送出中
     */
    public void setLoading(boolean value) {
        if (value == loading) {
            return;
        }
        loading = value;
        if (value) {
            disabledBeforeLoading = isDisable();
            graphicBeforeLoading = getGraphic();
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.getStyleClass().add("sv-button-spinner");
            setGraphic(spinner);
            setDisable(true);
            pseudoClassStateChanged(LOADING, true);
        } else {
            setGraphic(graphicBeforeLoading);
            setDisable(disabledBeforeLoading);
            graphicBeforeLoading = null;
            pseudoClassStateChanged(LOADING, false);
        }
    }

    /** @return 是否送出中 */
    public boolean isLoading() {
        return loading;
    }
}
