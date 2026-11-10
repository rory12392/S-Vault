package app.svault.desktop.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * 衝突對照的一個欄位（DiffRow、US-08）：「這台裝置」與「另一台裝置」兩個選項，一次只能選
 * 一個；每個選項顯示值與修改時間，被選的一側以 primary-container 標示並有勾選圖示（不只靠顏色）。密碼與受保護的
 * 欄位（T-16）預設遮罩，欄位名稱旁有「顯示…」按鈕。鍵盤：方向鍵左右切換並選取，空白鍵選取目前聚焦的選項。
 */
public final class DiffRow extends VBox {

    /** 選擇結果。 */
    public enum Side { THIS_DEVICE, OTHER_DEVICE }

    /**
     * 一邊的內容。
     *
     * @param value    值（{@code null} 或空字串顯示「（空白）」）
     * @param time     修改時間的文字（例：「修改於 2026/10/20 14:05」）；不顯示時 {@code null}
     * @param maskable 在要遮罩的欄位裡這個值要遮罩；「（已刪除）」這類說明文字不是使用者的內容，照常顯示
     */
    public record Choice(String value, String time, boolean maskable) {

        /**
         * 一般的值（要遮罩的欄位裡會遮罩）。
         *
         * @param value 值
         * @param time  修改時間的文字；不顯示時 {@code null}
         */
        public Choice(String value, String time) {
            this(value, time, true);
        }
    }

    private static final String MASK = "••••••••";
    private static final PseudoClass MISSING = PseudoClass.getPseudoClass("missing");

    private final ToggleGroup group = new ToggleGroup();
    private final ToggleButton left;
    private final ToggleButton right;
    private final ObjectProperty<Side> selected = new SimpleObjectProperty<>(this, "selected");
    private final BooleanProperty revealed = new SimpleBooleanProperty(this, "revealed");
    private final boolean secret;
    private boolean clearing;

    /**
     * @param field      欄位名稱
     * @param thisValue  這台的值
     * @param otherValue 另一台的值
     */
    public DiffRow(String field, String thisValue, String otherValue) {
        this(field, new Choice(thisValue, null), new Choice(otherValue, null), false);
    }

    /**
     * @param field     欄位名稱
     * @param thisSide  這台裝置
     * @param otherSide 另一台裝置
     * @param secret    密碼或受保護的欄位：預設遮罩，可切換顯示
     */
    public DiffRow(String field, Choice thisSide, Choice otherSide, boolean secret) {
        this.secret = secret;
        getStyleClass().add("sv-diff-row");
        Label name = new Label(field);
        name.getStyleClass().add("sv-diff-field");
        HBox header = new HBox(name);
        header.getStyleClass().add("sv-diff-header");
        left = option(UiText.get("diff.thisDevice"), thisSide, Side.THIS_DEVICE);
        right = option(UiText.get("diff.otherDevice"), otherSide, Side.OTHER_DEVICE);
        if (secret) {
            IconButton reveal = new IconButton(IconName.VISIBILITY, UiText.format("field.show", field));
            reveal.getStyleClass().add("sv-diff-reveal");
            reveal.setOnAction(e -> setRevealed(!isRevealed()));
            revealed.addListener((obs, old, now) -> reveal.setIcon(now ? IconName.VISIBILITY_OFF : IconName.VISIBILITY,
                    UiText.format(now ? "field.hide" : "field.show", field)));
            header.getChildren().add(reveal);
        }
        HBox options = new HBox(left, right);
        options.getStyleClass().add("sv-diff-options");
        // 已選的選項不能再點成「都不選」
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null && !clearing) {
                old.setSelected(true);
            } else if (now != null) {
                selected.set((Side) now.getUserData());
                setMissing(false);
            }
        });
        getChildren().addAll(header, options);
    }

    private ToggleButton option(String side, Choice choice, Side data) {
        Label sideLabel = new Label(side);
        sideLabel.getStyleClass().add("sv-diff-side");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Region check = Icons.small(IconName.CHECK_CIRCLE);
        check.getStyleClass().add("sv-diff-check");
        HBox top = new HBox(sideLabel, spacer, check);
        top.getStyleClass().add("sv-diff-top");
        String plain = choice.value() == null || choice.value().isEmpty() ? UiText.get("diff.empty") : choice.value();
        boolean maskable = secret && choice.maskable() && choice.value() != null && !choice.value().isEmpty();
        Label valueLabel = new Label(maskable ? MASK : plain);
        valueLabel.getStyleClass().add("sv-diff-value");
        if (maskable) {
            valueLabel.getStyleClass().add("sv-diff-secret");
        }
        // 不換行：換行的 Label 放在按鈕的 graphic 裡，偏好高度會以極窄寬度計算而撐得很高；
        // 過長時以省略號結尾，完整內容放在可讀名稱與提示框
        valueLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        VBox box = new VBox(top, valueLabel);
        box.getStyleClass().add("sv-diff-box");
        if (choice.time() != null) {
            Label time = new Label(choice.time());
            time.getStyleClass().add("sv-diff-time");
            box.getChildren().add(time);
        }
        ToggleButton b = new ToggleButton();
        b.setGraphic(box);
        b.getStyleClass().add("sv-diff-option");
        b.setUserData(data);
        b.setToggleGroup(group);
        Tooltip tip = new Tooltip();
        b.setTooltip(maskable ? null : tip);
        tip.setText(plain);
        Runnable describe = () -> {
            boolean masked = maskable && !isRevealed();
            valueLabel.setText(masked ? MASK : plain);
            String shown = masked ? UiText.get("field.masked") : plain;
            String base = choice.time() == null ? UiText.format("diff.accessible", side, shown)
                    : UiText.format("diff.accessibleTime", side, shown, choice.time());
            b.setAccessibleText(b.isSelected() ? UiText.format("diff.accessibleChosen", base) : base);
            b.setTooltip(masked ? null : tip);
        };
        describe.run();
        revealed.addListener((obs, old, now) -> describe.run());
        b.selectedProperty().addListener((obs, old, now) -> describe.run());
        // 勾選圖示只在被選的一側出現；不用 managed=false，避免選取時版面跳動
        check.visibleProperty().bind(b.selectedProperty());
        // 兩個選項平分寬度：偏好寬度相同，剩下的空間才會平均分配
        b.setMinWidth(0);
        b.setPrefWidth(0);
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(b, Priority.ALWAYS);
        b.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.RIGHT) {
                ToggleButton target = e.getCode() == KeyCode.LEFT ? left : right;
                target.setSelected(true);
                target.requestFocus();
                e.consume();
            }
        });
        return b;
    }

    /** @return 選擇結果；還沒選時 {@code null}（畫面據此顯示「未選完」） */
    public ObjectProperty<Side> selectedProperty() {
        return selected;
    }

    /** @param side 選擇（{@code null} 清除，展示頁用） */
    public void select(Side side) {
        if (side == null) {
            clearing = true;
            group.selectToggle(null);
            clearing = false;
            selected.set(null);
        } else {
            (side == Side.THIS_DEVICE ? left : right).setSelected(true);
        }
    }

    /** @return 密碼目前顯示明文 */
    public boolean isRevealed() {
        return revealed.get();
    }

    /** @param value 密碼是否顯示明文（非密碼欄位沒有作用） */
    public void setRevealed(boolean value) {
        revealed.set(secret && value);
    }

    /** @return 這一欄要遮罩（密碼或受保護的欄位） */
    public boolean isSecret() {
        return secret;
    }

    /**
     * 標示「還有欄位沒有選擇」（欄位名稱改用 danger 色；說明文字由畫面顯示，不只靠顏色）。選了一邊後自動取消。
     *
     * @param value 是否標示
     */
    public void setMissing(boolean value) {
        pseudoClassStateChanged(MISSING, value);
    }

    /** 焦點移到已選的一側；還沒選時移到「這台裝置」。 */
    public void focusChoice() {
        (selected.get() == Side.OTHER_DEVICE ? right : left).requestFocus();
    }
}
