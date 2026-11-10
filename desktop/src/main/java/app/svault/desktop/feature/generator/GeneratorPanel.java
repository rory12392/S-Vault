package app.svault.desktop.feature.generator;

import app.svault.core.feature.generator.CharClass;
import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.feature.generator.GeneratorSettings.Mode;
import app.svault.core.feature.generator.PassphraseOptions;
import app.svault.core.feature.generator.PasswordOptions;
import app.svault.core.security.PasswordStrength;
import app.svault.desktop.ui.IconButton;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.StrengthMeter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * 產生器的內容：結果（等寬字）＋「重新產生」、強度條（AC-04-4）、類型切換、隨機密碼的長度與四種字元
 * （AC-04-1）、通行片語的單字數與分隔符號（AC-04-2）。編輯畫面的彈出面板與選單的獨立頁共用，動作按鈕由外層放。
 *
 * <p>全部是 JavaFX 內建控制項（滑桿、數字欄、核取方塊、下拉）與共用元件，鍵盤可操作；樣式只用 token
 * （{@code generator.css}）。
 */
final class GeneratorPanel extends VBox {

    private static final String STYLESHEET = GeneratorPanel.class.getResource("generator.css").toExternalForm();

    private final GeneratorModel model;
    private final Label result = new Label();
    private final Label invalid;
    private final StrengthMeter meter = new StrengthMeter();
    private final ToggleButton passwordMode;
    private final ToggleButton passphraseMode;
    private final VBox passwordOptions;
    private final VBox passphraseOptions;
    private final Slider lengthSlider = new Slider(PasswordOptions.MIN_LENGTH, PasswordOptions.MAX_LENGTH,
            PasswordOptions.MIN_LENGTH);
    private final Spinner<Integer> lengthField = new Spinner<>();
    private final Map<CharClass, CheckBox> classBoxes = new EnumMap<>(CharClass.class);
    private final Spinner<Integer> wordsField = new Spinner<>();
    private final ComboBox<String> separatorBox = new ComboBox<>();
    /** 由 model 回寫控制項時為 true，避免控制項的監聽器又改回 model。 */
    private boolean refreshing;

    /**
     * @param model 狀態
     * @param text  模組的文字
     */
    GeneratorPanel(GeneratorModel model, ResourceBundle text) {
        this.model = model;
        getStylesheets().add(STYLESHEET);
        getStyleClass().add("sv-gen-panel");

        result.getStyleClass().addAll("sv-mono", "sv-gen-result-text");
        result.setId("gen-result");
        result.setWrapText(true);
        result.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(result, Priority.ALWAYS);
        IconButton regenerate = new IconButton(IconName.SYNC, text.getString("result.regenerate"));
        regenerate.setId("gen-regenerate");
        regenerate.setOnAction(e -> model.regenerate());
        HBox resultBox = new HBox(result, regenerate);
        resultBox.getStyleClass().add("sv-gen-result");
        Label resultLabel = new Label(text.getString("result.label"));
        resultLabel.getStyleClass().add("sv-field-label");
        resultLabel.setLabelFor(result);
        invalid = new Label(text.getString("result.invalid"));
        invalid.getStyleClass().add("sv-field-error");
        invalid.setId("gen-invalid");
        meter.setId("gen-strength");

        ToggleGroup modes = new ToggleGroup();
        passwordMode = modeButton(text.getString("mode.password"), modes, Mode.PASSWORD, "gen-mode-password");
        passphraseMode = modeButton(text.getString("mode.passphrase"), modes, Mode.PASSPHRASE, "gen-mode-passphrase");
        HBox modeRow = new HBox(passwordMode, passphraseMode);
        modeRow.getStyleClass().add("sv-segmented");
        Label modeLabel = new Label(text.getString("mode.label"));
        modeLabel.getStyleClass().add("sv-field-label");
        modeLabel.setLabelFor(passwordMode);
        // 已選的那顆再按一次不會變成都沒選
        modes.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null && old != null) {
                old.setSelected(true);
            }
        });

        passwordOptions = new VBox(lengthRow(text), classRow(text));
        passwordOptions.getStyleClass().add("sv-gen-options");
        passphraseOptions = new VBox(wordsRow(text), separatorRow(text));
        passphraseOptions.getStyleClass().add("sv-gen-options");

        VBox resultSection = new VBox(resultLabel, resultBox, meter, invalid);
        resultSection.getStyleClass().add("sv-gen-section");
        VBox modeSection = new VBox(modeLabel, modeRow);
        modeSection.getStyleClass().add("sv-gen-section");
        getChildren().addAll(resultSection, modeSection, passwordOptions, passphraseOptions);

        model.addListener(this::refresh);
        refresh();
    }

    /** @return 類型切換的第一顆（開啟時的焦點） */
    ToggleButton firstControl() {
        return passwordMode;
    }

    private ToggleButton modeButton(String label, ToggleGroup group, Mode mode, String id) {
        ToggleButton b = new ToggleButton(label);
        b.setToggleGroup(group);
        b.setId(id);
        b.selectedProperty().addListener((obs, old, now) -> {
            if (now && !refreshing) {
                model.setMode(mode);
            }
        });
        return b;
    }

    private HBox lengthRow(ResourceBundle text) {
        lengthSlider.setId("gen-length-slider");
        lengthSlider.setBlockIncrement(1);
        lengthSlider.setMajorTickUnit(1);
        lengthSlider.setSnapToTicks(true);
        lengthSlider.getStyleClass().add("sv-gen-slider");
        lengthSlider.setAccessibleText(text.getString("length.label"));
        lengthSlider.valueProperty().addListener((obs, old, now) -> {
            if (!refreshing) {
                model.setLength((int) Math.round(now.doubleValue()), !lengthSlider.isValueChanging());
            }
        });
        // 拖曳中不存檔，放開時存一次（每次存檔都會排一次寫檔）
        lengthSlider.valueChangingProperty().addListener((obs, old, now) -> {
            if (!now) {
                model.save();
            }
        });
        lengthField.setId("gen-length");
        configure(lengthField, PasswordOptions.MIN_LENGTH, PasswordOptions.MAX_LENGTH, text);
        lengthField.valueProperty().addListener((obs, old, now) -> {
            if (!refreshing && now != null) {
                model.setLength(now, true);
            }
        });
        Label label = new Label(text.getString("length.label"));
        label.getStyleClass().add("sv-gen-row-label");
        label.setLabelFor(lengthField);
        HBox row = new HBox(label, lengthSlider, lengthField);
        row.getStyleClass().add("sv-gen-row");
        return row;
    }

    private FlowPane classRow(ResourceBundle text) {
        FlowPane row = new FlowPane();
        row.getStyleClass().add("sv-gen-classes");
        for (CharClass c : CharClass.values()) {
            String key = switch (c) {
                case UPPER -> "class.upper";
                case LOWER -> "class.lower";
                case DIGIT -> "class.digit";
                case SYMBOL -> "class.symbol";
            };
            CheckBox box = new CheckBox(text.getString(key));
            box.setId("gen-" + key.replace('.', '-'));
            box.selectedProperty().addListener((obs, old, now) -> {
                if (!refreshing) {
                    model.setClass(c, now);
                }
            });
            classBoxes.put(c, box);
            row.getChildren().add(box);
        }
        return row;
    }

    private HBox wordsRow(ResourceBundle text) {
        wordsField.setId("gen-words");
        configure(wordsField, PassphraseOptions.MIN_WORDS, PassphraseOptions.MAX_WORDS, text);
        wordsField.valueProperty().addListener((obs, old, now) -> {
            if (!refreshing && now != null) {
                model.setWords(now);
            }
        });
        Label label = new Label(text.getString("words.label"));
        label.getStyleClass().add("sv-gen-row-label");
        label.setLabelFor(wordsField);
        HBox row = new HBox(label, wordsField);
        row.getStyleClass().add("sv-gen-row");
        return row;
    }

    private HBox separatorRow(ResourceBundle text) {
        List<String> names = List.of(text.getString("separator.hyphen"), text.getString("separator.space"),
                text.getString("separator.period"), text.getString("separator.underscore"),
                text.getString("separator.none"));
        separatorBox.setId("gen-separator");
        separatorBox.getItems().setAll(PassphraseOptions.SEPARATORS);
        // 下拉顯示名稱（空白、不分隔看不到），值仍是分隔符號本身；兩份清單依 SEPARATORS 的順序對應
        StringConverter<String> converter = new StringConverter<>() {
            @Override
            public String toString(String sep) {
                int i = sep == null ? -1 : PassphraseOptions.SEPARATORS.indexOf(sep);
                return i < 0 ? "" : names.get(i);
            }

            @Override
            public String fromString(String name) {
                int i = names.indexOf(name);
                return i < 0 ? null : PassphraseOptions.SEPARATORS.get(i);
            }
        };
        separatorBox.setConverter(converter);
        separatorBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : converter.toString(item));
            }
        });
        separatorBox.valueProperty().addListener((obs, old, now) -> {
            if (!refreshing && now != null) {
                model.setSeparator(now);
            }
        });
        Label label = new Label(text.getString("separator.label"));
        label.getStyleClass().add("sv-gen-row-label");
        label.setLabelFor(separatorBox);
        HBox row = new HBox(label, separatorBox);
        row.getStyleClass().add("sv-gen-row");
        return row;
    }

    private static void configure(Spinner<Integer> spinner, int min, int max, ResourceBundle text) {
        // 增減鈕是 JavaFX 內建的，預設讀出英文；skin 建立後才找得到
        spinner.skinProperty().addListener((obs, old, skin) -> {
            Node up = spinner.lookup(".increment-arrow-button");
            Node down = spinner.lookup(".decrement-arrow-button");
            if (up != null && down != null) {
                up.setAccessibleText(text.getString("spinner.increase"));
                down.setAccessibleText(text.getString("spinner.decrease"));
            }
        });
        SpinnerValueFactory.IntegerSpinnerValueFactory factory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, min);
        // 打了非數字時保留原本的值（預設的轉換器會丟例外）；超出範圍由 factory 夾到範圍內
        factory.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return value == null ? "" : value.toString();
            }

            @Override
            public Integer fromString(String s) {
                try {
                    int v = Integer.parseInt(s.strip());
                    return Math.max(min, Math.min(max, v));
                } catch (NumberFormatException e) {
                    return factory.getValue();
                }
            }
        });
        spinner.setValueFactory(factory);
        spinner.setEditable(true);
        // 寬度用欄數（最多三位數，再加內距）而不是寫死像素
        spinner.getEditor().setPrefColumnCount(4);
    }

    private void refresh() {
        refreshing = true;
        try {
            boolean password = model.mode() == Mode.PASSWORD;
            passwordMode.setSelected(password);
            passphraseMode.setSelected(!password);
            show(passwordOptions, password);
            show(passphraseOptions, !password);
            lengthSlider.setValue(model.length());
            lengthField.getValueFactory().setValue(model.length());
            classBoxes.forEach((c, box) -> box.setSelected(model.has(c)));
            wordsField.getValueFactory().setValue(model.words());
            separatorBox.setValue(model.separator());

            boolean bad = model.invalid();
            result.setText(model.display());
            show(invalid, bad);
            show(meter, !bad);
            meter.setStrength(model.strength().map(GeneratorPanel::meterStrength).orElse(StrengthMeter.Strength.NONE));
        } finally {
            refreshing = false;
        }
    }

    private static StrengthMeter.Strength meterStrength(PasswordStrength s) {
        return switch (s) {
            case WEAK -> StrengthMeter.Strength.WEAK;
            case MEDIUM -> StrengthMeter.Strength.MEDIUM;
            case STRONG -> StrengthMeter.Strength.STRONG;
        };
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
