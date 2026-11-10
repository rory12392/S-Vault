package app.svault.android.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import app.svault.android.R;
import com.google.android.material.button.MaterialButton;

/**
 * 空清單、找不到結果、模組錯誤：圖示＋標題＋說明＋（可選）行動按鈕。
 */
public class EmptyState extends LinearLayout {

    private final ImageView icon;
    private final TextView title;
    private final TextView body;
    private final MaterialButton action;

    public EmptyState(Context context) {
        this(context, null);
    }

    public EmptyState(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);
        int xl = getResources().getDimensionPixelSize(R.dimen.sv_space_xl);
        setPadding(xl, xl, xl, xl);
        LayoutInflater.from(context).inflate(R.layout.sv_empty_state, this, true);
        icon = findViewById(R.id.sv_empty_icon);
        title = findViewById(R.id.sv_empty_title);
        body = findViewById(R.id.sv_empty_body);
        action = findViewById(R.id.sv_empty_action);
        show(R.drawable.sv_ic_folder, "", null, null, null);
    }

    /**
     * @param iconRes     圖示（{@code sv_ic_*}）
     * @param titleText   標題
     * @param bodyText    說明；沒有時為 {@code null}
     * @param actionLabel 行動按鈕文字（動詞）；「無行動」變體為 {@code null}
     * @param onAction    行動
     */
    public void show(@DrawableRes int iconRes, CharSequence titleText, @Nullable CharSequence bodyText,
            @Nullable CharSequence actionLabel, @Nullable View.OnClickListener onAction) {
        icon.setImageResource(iconRes);
        title.setText(titleText);
        body.setText(bodyText);
        body.setVisibility(bodyText == null ? GONE : VISIBLE);
        boolean hasAction = actionLabel != null && onAction != null;
        action.setVisibility(hasAction ? VISIBLE : GONE);
        action.setText(actionLabel);
        action.setOnClickListener(onAction);
    }
}
