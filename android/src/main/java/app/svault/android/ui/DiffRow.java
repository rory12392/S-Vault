package app.svault.android.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import app.svault.android.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.radiobutton.MaterialRadioButton;

/**
 * 衝突對照的一個欄位：「這台裝置」與「另一台裝置」的值擇一。
 * 被選的一側有 primary-container 底色與選取圓點（不只靠顏色）；密碼與受保護的欄位（T-16）預設遮罩、可顯示。
 * 還沒選的欄位可標示「未選」（欄位名稱改用 danger 色；畫面另外顯示文字，不只靠顏色）。
 */
public class DiffRow extends LinearLayout {

    /** 選了哪一邊。 */
    public enum Choice {
        NONE, THIS_DEVICE, OTHER_DEVICE
    }

    /** 選擇改變時的通知。 */
    public interface OnChoiceListener {

        /**
         * @param choice 目前的選擇
         */
        void onChoice(Choice choice);
    }

    private static final String MASK = "••••••••";

    private final TextView field;
    private final MaterialButton reveal;
    private final RadioGroup group;
    private final MaterialRadioButton thisOption;
    private final MaterialRadioButton otherOption;

    private CharSequence fieldName = "";
    private CharSequence thisValue = "";
    private CharSequence otherValue = "";
    @Nullable private CharSequence thisTime;
    @Nullable private CharSequence otherTime;
    private boolean secret;
    private boolean thisMaskable;
    private boolean otherMaskable;
    private boolean revealed;
    @Nullable private OnChoiceListener listener;

    public DiffRow(Context context) {
        this(context, null);
    }

    public DiffRow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        int sm = getResources().getDimensionPixelSize(R.dimen.sv_space_sm);
        int lg = getResources().getDimensionPixelSize(R.dimen.sv_space_lg);
        setPaddingRelative(lg, sm, lg, sm);
        LayoutInflater.from(context).inflate(R.layout.sv_diff_row, this, true);
        field = findViewById(R.id.sv_diff_field);
        reveal = findViewById(R.id.sv_diff_reveal);
        group = findViewById(R.id.sv_diff_group);
        thisOption = findViewById(R.id.sv_diff_this);
        otherOption = findViewById(R.id.sv_diff_other);
        reveal.setOnClickListener(v -> {
            revealed = !revealed;
            render();
        });
        group.setOnCheckedChangeListener((g, id) -> {
            if (id != View.NO_ID) {
                setMissing(false);
            }
            if (listener != null) {
                listener.onChoice(getChoice());
            }
        });
    }

    /**
     * @param name   欄位名稱
     * @param mine   這台裝置的值（空字串顯示「（空白）」）
     * @param mineAt 這台裝置的修改時間（{@code yyyy/MM/dd HH:mm}）；沒有時為 {@code null}
     * @param theirs 另一台裝置的值
     * @param theirsAt 另一台裝置的修改時間；沒有時為 {@code null}
     * @param isSecret 密碼欄：預設遮罩，可顯示
     */
    public void bind(CharSequence name, CharSequence mine, @Nullable CharSequence mineAt, CharSequence theirs,
            @Nullable CharSequence theirsAt, boolean isSecret) {
        bind(name, mine, mineAt, true, theirs, theirsAt, true, isSecret);
    }

    /**
     * @param name          欄位名稱
     * @param mine          這台裝置的值（空字串顯示「（空白）」）
     * @param mineAt        這台裝置的修改時間；沒有時為 {@code null}
     * @param mineMaskable  在要遮罩的欄位裡這一側要遮罩；「（已刪除）」這類說明文字不是使用者的內容，照常顯示
     * @param theirs        另一台裝置的值
     * @param theirsAt      另一台裝置的修改時間；沒有時為 {@code null}
     * @param theirsMaskable 同 {@code mineMaskable}，另一台裝置
     * @param isSecret      密碼或受保護的欄位：預設遮罩，可顯示
     */
    public void bind(CharSequence name, CharSequence mine, @Nullable CharSequence mineAt, boolean mineMaskable,
            CharSequence theirs, @Nullable CharSequence theirsAt, boolean theirsMaskable, boolean isSecret) {
        thisMaskable = mineMaskable;
        otherMaskable = theirsMaskable;
        fieldName = name;
        thisValue = mine == null ? "" : mine;
        otherValue = theirs == null ? "" : theirs;
        thisTime = mineAt;
        otherTime = theirsAt;
        secret = isSecret;
        revealed = false;
        field.setText(name);
        reveal.setVisibility(isSecret ? VISIBLE : GONE);
        group.clearCheck();
        setMissing(false);
        render();
    }

    /** @return 這一欄要遮罩（密碼或受保護的欄位） */
    public boolean isSecret() {
        return secret;
    }

    /** @return 目前顯示明文 */
    public boolean isRevealed() {
        return revealed;
    }

    /** @param value 是否顯示明文（不遮罩的欄位沒有作用） */
    public void setRevealed(boolean value) {
        revealed = secret && value;
        render();
    }

    /**
     * 標示「還有欄位沒有選擇」：欄位名稱改用 danger 色。選了一邊後自動取消。
     *
     * @param value 是否標示
     */
    public void setMissing(boolean value) {
        field.setTextColor(value ? ContextCompat.getColor(getContext(), R.color.sv_danger)
                : MaterialColors.getColor(field, com.google.android.material.R.attr.colorOnSurface));
    }

    /** 無障礙焦點移到已選的一側；還沒選時移到「這台裝置」（「還有欄位沒有選擇」時）。 */
    public void focusChoice() {
        View target = getChoice() == Choice.OTHER_DEVICE ? otherOption : thisOption;
        target.requestFocus();
        target.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED);
    }

    /** @return 目前的選擇；還沒選時為 {@link Choice#NONE} */
    public Choice getChoice() {
        int id = group.getCheckedRadioButtonId();
        if (id == R.id.sv_diff_this) {
            return Choice.THIS_DEVICE;
        }
        return id == R.id.sv_diff_other ? Choice.OTHER_DEVICE : Choice.NONE;
    }

    /** @param choice 要選的一邊（「全部用這台裝置的」等快速按鈕用） */
    public void setChoice(Choice choice) {
        if (choice == Choice.THIS_DEVICE) {
            group.check(R.id.sv_diff_this);
        } else if (choice == Choice.OTHER_DEVICE) {
            group.check(R.id.sv_diff_other);
        } else {
            group.clearCheck();
        }
    }

    /** @param l 選擇改變時 */
    public void setOnChoiceListener(@Nullable OnChoiceListener l) {
        listener = l;
    }

    private void render() {
        boolean thisMasked = masked(thisValue, thisMaskable);
        boolean otherMasked = masked(otherValue, otherMaskable);
        thisOption.setText(option(R.string.sv_diff_this_device, thisValue, thisTime, thisMasked, false));
        otherOption.setText(option(R.string.sv_diff_other_device, otherValue, otherTime, otherMasked, false));
        // 遮罩時 TalkBack 讀「已遮罩」，不讀圓點
        thisOption.setContentDescription(thisMasked
                ? option(R.string.sv_diff_this_device, thisValue, thisTime, true, true) : null);
        otherOption.setContentDescription(otherMasked
                ? option(R.string.sv_diff_other_device, otherValue, otherTime, true, true) : null);
        reveal.setIconResource(revealed ? R.drawable.sv_ic_visibility_off : R.drawable.sv_ic_visibility);
        reveal.setContentDescription(getResources().getString(
                revealed ? R.string.sv_field_hide : R.string.sv_field_show, fieldName));
    }

    private boolean masked(CharSequence value, boolean maskable) {
        return secret && !revealed && maskable && value.length() > 0;
    }

    private CharSequence option(int sideRes, CharSequence value, @Nullable CharSequence time, boolean masked,
            boolean forSpeech) {
        String side = getResources().getString(sideRes);
        CharSequence shown;
        if (value.length() == 0) {
            shown = getResources().getString(R.string.sv_diff_empty);
        } else if (masked) {
            shown = forSpeech ? getResources().getString(R.string.sv_field_masked) : MASK;
        } else {
            shown = value;
        }
        return time == null
                ? getResources().getString(R.string.sv_diff_option, side, shown)
                : getResources().getString(R.string.sv_diff_option_time, side, shown, time);
    }
}
