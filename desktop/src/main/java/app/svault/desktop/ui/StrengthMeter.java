package app.svault.desktop.ui;

import java.util.List;
import java.util.Locale;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

/**
 * 強度條（AC-04-4）：弱 danger、中 warning、強 success，一律附文字，不只靠顏色。
 * 不可聚焦；文字由螢幕閱讀器讀出。強度的計算在 T-09 的 {@code StrengthEstimator}，這裡只負責顯示。
 */
public final class StrengthMeter extends HBox {

    /** 顯示的強度。 */
    public enum Strength {
        NONE(0), WEAK(1), MEDIUM(2), STRONG(3);

        private final int filled;

        Strength(int filled) {
            this.filled = filled;
        }

        /** @return 亮起的格數（0～3） */
        public int filled() {
            return filled;
        }

        String styleClass() {
            return "sv-strength-" + name().toLowerCase(Locale.ROOT);
        }
    }

    private static final PseudoClass FILLED = PseudoClass.getPseudoClass("filled");

    private final List<Region> segments = List.of(new Region(), new Region(), new Region());
    private final Label text = new Label();
    private Strength strength = Strength.NONE;

    /** 建立空的強度條（{@link Strength#NONE}）。 */
    public StrengthMeter() {
        getStyleClass().add("sv-strength");
        HBox bar = new HBox();
        bar.getStyleClass().add("sv-strength-bar");
        for (Region segment : segments) {
            segment.getStyleClass().add("sv-strength-segment");
            HBox.setHgrow(segment, Priority.ALWAYS);
            bar.getChildren().add(segment);
        }
        HBox.setHgrow(bar, Priority.ALWAYS);
        text.getStyleClass().add("sv-strength-text");
        setFocusTraversable(false);
        getChildren().addAll(bar, text);
        setStrength(Strength.NONE);
    }

    /** @param value 新的強度 */
    public final void setStrength(Strength value) {
        getStyleClass().remove(strength.styleClass());
        strength = value;
        getStyleClass().add(value.styleClass());
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).pseudoClassStateChanged(FILLED, i < value.filled());
        }
        String label = value == Strength.NONE ? ""
                : UiText.get("strength." + value.name().toLowerCase(Locale.ROOT));
        text.setText(label);
        String accessible = label.isEmpty() ? null : UiText.format("strength.accessible", label);
        setAccessibleText(accessible);
        // 螢幕閱讀器讀到的是裡面的文字（HBox 沒有無障礙角色）
        text.setAccessibleText(accessible);
    }

    /** @return 目前的強度 */
    public Strength getStrength() {
        return strength;
    }
}
