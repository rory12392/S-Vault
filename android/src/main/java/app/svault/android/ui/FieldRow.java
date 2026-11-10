package app.svault.android.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.core.widget.TextViewCompat;
import app.svault.android.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

/**
 * 條目詳細頁的一個欄位：標籤＋值＋動作按鈕（顯示／隱藏、開啟、複製），每個動作是獨立的 IconButton。
 */
public class FieldRow extends LinearLayout {

    /** 欄位種類。 */
    public enum Kind {
        /** 一般文字（帳號）：複製。 */
        TEXT,
        /** 密碼：預設遮罩，可顯示（AC-02-5），明文用等寬字；複製。 */
        PASSWORD,
        /** 網址：開啟（系統瀏覽器）＋複製。 */
        URL,
        /** 備註：多行、可選取文字；複製。 */
        NOTES
    }

    /** 遮罩時顯示的字元數固定，不透露密碼長度。 */
    private static final String MASK = "••••••••";

    private final View textBlock;
    private final TextView label;
    private final TextView value;
    private final TextView copiedMark;
    private final MaterialButton reveal;
    private final MaterialButton open;
    private final MaterialButton copy;

    private Kind kind = Kind.TEXT;
    private CharSequence labelText = "";
    private CharSequence valueText = "";
    private boolean revealed;
    @Nullable private OnRevealChangedListener revealListener;

    /** 密碼顯示狀態改變時的通知（例如上鎖前改回遮罩）。 */
    public interface OnRevealChangedListener {

        /**
         * @param revealed 目前是否顯示明文
         */
        void onRevealChanged(boolean revealed);
    }

    public FieldRow(Context context) {
        this(context, null);
    }

    public FieldRow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.sv_row_min_height));
        int lg = getResources().getDimensionPixelSize(R.dimen.sv_space_lg);
        int xs = getResources().getDimensionPixelSize(R.dimen.sv_space_xs);
        setPaddingRelative(lg, 0, xs, 0);
        LayoutInflater.from(context).inflate(R.layout.sv_field_row, this, true);
        textBlock = findViewById(R.id.sv_field_text);
        label = findViewById(R.id.sv_field_label);
        value = findViewById(R.id.sv_field_value);
        copiedMark = findViewById(R.id.sv_field_copied);
        reveal = findViewById(R.id.sv_field_reveal);
        open = findViewById(R.id.sv_field_open);
        copy = findViewById(R.id.sv_field_copy);
        reveal.setOnClickListener(v -> setRevealed(!revealed));
    }

    /**
     * @param kind      欄位種類
     * @param labelText 欄位名稱（「帳號」「密碼」…）
     * @param valueText 值；密碼在遮罩時不會顯示
     */
    public void bind(Kind kind, CharSequence labelText, CharSequence valueText) {
        this.kind = kind;
        this.labelText = labelText;
        this.valueText = valueText == null ? "" : valueText;
        this.revealed = false;
        label.setText(labelText);
        reveal.setVisibility(kind == Kind.PASSWORD ? VISIBLE : GONE);
        open.setVisibility(kind == Kind.URL ? VISIBLE : GONE);
        value.setTextIsSelectable(kind == Kind.NOTES);
        open.setContentDescription(getResources().getString(R.string.sv_field_open, labelText));
        copy.setContentDescription(getResources().getString(R.string.sv_field_copy, labelText));
        setCopied(false);
        render();
    }

    /**
     * @param show 顯示明文時為 {@code true}；只對密碼欄有效
     */
    public void setRevealed(boolean show) {
        if (kind != Kind.PASSWORD || revealed == show) {
            return;
        }
        revealed = show;
        render();
        if (revealListener != null) {
            revealListener.onRevealChanged(show);
        }
    }

    /** @return 密碼目前是否顯示明文 */
    public boolean isRevealed() {
        return revealed;
    }

    /**
     * @param copied 剛複製過這個欄位時為 {@code true}（顯示「已複製」標記）
     */
    public void setCopied(boolean copied) {
        copiedMark.setVisibility(copied ? VISIBLE : GONE);
        updateDescription();
    }

    /** @param listener 按下「複製」時 */
    public void setOnCopyListener(@Nullable View.OnClickListener listener) {
        copy.setOnClickListener(listener);
    }

    /** @param listener 按下「開啟」時（網址欄） */
    public void setOnOpenListener(@Nullable View.OnClickListener listener) {
        open.setOnClickListener(listener);
    }

    /** @param listener 密碼顯示狀態改變時 */
    public void setOnRevealChangedListener(@Nullable OnRevealChangedListener listener) {
        revealListener = listener;
    }

    private void render() {
        boolean masked = kind == Kind.PASSWORD && !revealed;
        value.setText(masked ? MASK : valueText);
        TextViewCompat.setTextAppearance(value, kind == Kind.PASSWORD
                ? R.style.TextAppearance_SVault_Mono
                : com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        // textAppearance 可能帶自己的文字色；值一律用 colorOnSurface
        value.setTextColor(MaterialColors.getColor(value, com.google.android.material.R.attr.colorOnSurface));
        reveal.setIconResource(revealed ? R.drawable.sv_ic_visibility_off : R.drawable.sv_ic_visibility);
        reveal.setContentDescription(getResources().getString(
                revealed ? R.string.sv_field_hide : R.string.sv_field_show, labelText));
        updateDescription();
    }

    private void updateDescription() {
        // 遮罩時讀「密碼，已遮罩」，不讀出圓點；複製後加上「已複製」
        boolean masked = kind == Kind.PASSWORD && !revealed;
        StringBuilder d = new StringBuilder().append(labelText).append('，')
                .append(masked ? getResources().getString(R.string.sv_field_masked) : valueText);
        if (copiedMark.getVisibility() == VISIBLE) {
            d.append('，').append(getResources().getString(R.string.sv_field_copied));
        }
        textBlock.setContentDescription(d);
    }
}
