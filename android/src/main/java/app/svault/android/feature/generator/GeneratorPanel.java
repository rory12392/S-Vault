package app.svault.android.feature.generator;

import android.content.res.Resources;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import app.svault.android.R;
import app.svault.android.ui.StrengthMeter;
import app.svault.core.feature.generator.CharClass;
import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.feature.generator.GeneratorSettings.Mode;
import app.svault.core.feature.generator.PassphraseOptions;
import app.svault.core.module.ModuleGuard;
import app.svault.core.security.PasswordStrength;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 產生器的內容（{@code gen_panel.xml}）：把 {@link GeneratorModel} 畫到畫面上、把操作交給它。產生器頁
 * （{@link GeneratorPageFragment}，T-42）與編輯畫面「產生」的底部 sheet（{@link GeneratorSheet}，T-44）共用。
 *
 * <p>每個操作都包在呼叫端給的 {@link ModuleGuard} 裡（出錯時呼叫端顯示錯誤提示，畫面照常）。外面的按鈕（「複製」、
 * 「使用這個密碼」）交給建構子，選項不合法時一起停用。只在主執行緒使用。
 */
final class GeneratorPanel {

    private final GeneratorModel model;
    /** 掛在模型上的監聽（拿掉時要同一個物件）。 */
    private final Runnable renderer = this::render;
    private final ModuleGuard guard;
    private final List<View> resultActions;
    private final Resources res;
    private final String[] separatorNames;
    private final Map<CharClass, MaterialCheckBox> classBoxes = new EnumMap<>(CharClass.class);

    private final TextView result;
    private final TextView invalid;
    private final MaterialButton regenerate;
    private final StrengthMeter strength;
    private final MaterialButtonToggleGroup mode;
    private final View passwordOptions;
    private final View passphraseOptions;
    private final Slider lengthSlider;
    private final TextView lengthField;
    private final TextView wordsValue;
    private final Slider words;
    private final MaterialAutoCompleteTextView separator;

    /** 程式在更新畫面（不是使用者操作）：元件的監聽不要再改回模型。 */
    private boolean rendering;
    /** 使用者正在拖曳長度滑桿：拖曳中不存檔，放開時存一次。 */
    private boolean dragging;

    /**
     * @param root          含 {@code gen_panel.xml} 的畫面
     * @param model         狀態（在 {@link GeneratorViewModel}）
     * @param guard         模組的錯誤邊界
     * @param resultActions 要有結果才能按的按鈕
     */
    GeneratorPanel(View root, GeneratorModel model, ModuleGuard guard, View... resultActions) {
        this.model = model;
        this.guard = guard;
        this.resultActions = List.of(resultActions);
        res = root.getResources();
        separatorNames = res.getStringArray(R.array.gen_separator_names);
        result = root.findViewById(R.id.gen_result);
        invalid = root.findViewById(R.id.gen_invalid);
        regenerate = root.findViewById(R.id.gen_regenerate);
        strength = root.findViewById(R.id.gen_strength);
        mode = root.findViewById(R.id.gen_mode);
        passwordOptions = root.findViewById(R.id.gen_password_options);
        passphraseOptions = root.findViewById(R.id.gen_passphrase_options);
        lengthSlider = root.findViewById(R.id.gen_length_slider);
        lengthField = root.findViewById(R.id.gen_length);
        wordsValue = root.findViewById(R.id.gen_words_value);
        words = root.findViewById(R.id.gen_words);
        separator = root.findViewById(R.id.gen_separator);
        classBoxes.put(CharClass.UPPER, root.findViewById(R.id.gen_class_upper));
        classBoxes.put(CharClass.LOWER, root.findViewById(R.id.gen_class_lower));
        classBoxes.put(CharClass.DIGIT, root.findViewById(R.id.gen_class_digit));
        classBoxes.put(CharClass.SYMBOL, root.findViewById(R.id.gen_class_symbol));
        bindOptions();
        regenerate.setOnClickListener(v -> act("regenerate", model::regenerate));
    }

    /** 開始跟著模型更新畫面（畫面建立後）。 */
    void attach() {
        model.addListener(renderer);
        render();
    }

    /** 畫面消失時呼叫：ViewModel 比畫面活得久，不能留著這個畫面的參考。 */
    void detach() {
        model.removeListener(renderer);
    }

    /**
     * 在模組的錯誤邊界裡執行操作；失敗時把畫面恢復成模型目前的樣子。
     *
     * @param action 動作名稱（記錄用）
     * @param body   操作
     */
    void act(String action, Runnable body) {
        if (!guard.run(GeneratorAndroidFeature.ID, action, body)) {
            render();
        }
    }

    private void bindOptions() {
        mode.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked && !rendering) {
                act("mode", () -> model.setMode(id == R.id.gen_mode_passphrase ? Mode.PASSPHRASE : Mode.PASSWORD));
            }
        });
        lengthSlider.setLabelFormatter(value -> String.valueOf((int) value));
        lengthSlider.addOnChangeListener((slider, value, fromUser) -> {
            // 拖曳中只重新產生；TalkBack 或鍵盤一格一格調整時沒有拖曳，每次都存
            if (fromUser && !rendering) {
                act("length", () -> model.setLength((int) value, !dragging));
            }
        });
        lengthSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                dragging = false;
                act("length", model::save);
            }
        });
        lengthField.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                applyLengthField();
            }
            return false;
        });
        lengthField.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                applyLengthField();
            }
        });
        for (Map.Entry<CharClass, MaterialCheckBox> e : classBoxes.entrySet()) {
            e.getValue().setOnCheckedChangeListener((CompoundButton b, boolean on) -> {
                if (!rendering) {
                    act("class", () -> model.setClass(e.getKey(), on));
                }
            });
        }
        words.setLabelFormatter(value -> String.valueOf((int) value));
        words.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser && !rendering) {
                act("words", () -> model.setWords((int) value));
            }
        });
        separator.setSimpleItems(separatorNames);
        separator.setOnItemClickListener((parent, v, position, id) ->
                act("separator", () -> model.setSeparator(PassphraseOptions.SEPARATORS.get(position))));
    }

    /** 數字欄：完成輸入或離開欄位時套用；超出 8–128 夾到範圍內，不是數字時恢復目前的長度。 */
    private void applyLengthField() {
        CharSequence text = lengthField.getText();
        int value;
        try {
            value = Integer.parseInt(text == null ? "" : text.toString().trim());
        } catch (NumberFormatException e) {
            value = model.length();
        }
        int typed = value;
        act("length", () -> model.setLength(typed, true));
        // 夾到範圍內或恢復時，模型沒有改變就不會通知：自己更新一次欄位
        lengthField.setText(String.valueOf(model.length()));
    }

    // 畫面消失時 detach() 拿掉監聽，之後不會再被呼叫
    private void render() {
        rendering = true;
        try {
            boolean password = model.mode() == Mode.PASSWORD;
            mode.check(password ? R.id.gen_mode_password : R.id.gen_mode_passphrase);
            passwordOptions.setVisibility(password ? View.VISIBLE : View.GONE);
            passphraseOptions.setVisibility(password ? View.GONE : View.VISIBLE);
            if (!dragging && lengthSlider.getValue() != model.length()) {
                lengthSlider.setValue(model.length());
            }
            if (!lengthField.hasFocus()) {
                lengthField.setText(String.valueOf(model.length()));
            }
            for (Map.Entry<CharClass, MaterialCheckBox> e : classBoxes.entrySet()) {
                e.getValue().setChecked(model.has(e.getKey()));
            }
            words.setValue(model.words());
            wordsValue.setText(res.getString(R.string.gen_words_value, model.words()));
            separator.setText(separatorNames[PassphraseOptions.SEPARATORS.indexOf(model.separator())], false);

            boolean bad = model.invalid();
            result.setText(model.display());
            result.setVisibility(bad ? View.GONE : View.VISIBLE);
            invalid.setVisibility(bad ? View.VISIBLE : View.GONE);
            regenerate.setEnabled(!bad);
            for (View v : resultActions) {
                v.setEnabled(!bad);
            }
            strength.setLevel(model.strength().map(GeneratorPanel::level).orElse(StrengthMeter.Level.NONE));
        } finally {
            rendering = false;
        }
    }

    private static StrengthMeter.Level level(PasswordStrength s) {
        return switch (s) {
            case WEAK -> StrengthMeter.Level.WEAK;
            case MEDIUM -> StrengthMeter.Level.MEDIUM;
            case STRONG -> StrengthMeter.Level.STRONG;
        };
    }
}
