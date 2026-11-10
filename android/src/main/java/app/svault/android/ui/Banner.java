package app.svault.android.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import app.svault.android.R;
import com.google.android.material.button.MaterialButton;

/**
 * 頁面層級的持續訊息：同步中、已合併、仍有衝突、檔案讀不到。在清單上方佔一列，可含一個動作按鈕。
 * 圖示＋文字表達種類，不只靠底色。
 */
public class Banner extends LinearLayout {

    /** 種類。 */
    public enum Kind {
        INFO(R.drawable.sv_ic_info, R.color.sv_primary_container, R.color.sv_on_primary_container),
        WARNING(R.drawable.sv_ic_warning, R.color.sv_warning_container, R.color.sv_on_warning_container),
        ERROR(R.drawable.sv_ic_error, R.color.sv_danger_container, R.color.sv_on_danger_container);

        @DrawableRes final int icon;
        @ColorRes final int container;
        @ColorRes final int onContainer;

        Kind(int icon, int container, int onContainer) {
            this.icon = icon;
            this.container = container;
            this.onContainer = onContainer;
        }
    }

    private final ImageView icon;
    private final TextView text;
    private final MaterialButton action;

    public Banner(Context context) {
        this(context, null);
    }

    public Banner(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        int md = getResources().getDimensionPixelSize(R.dimen.sv_space_md);
        int xs = getResources().getDimensionPixelSize(R.dimen.sv_space_xs);
        setPaddingRelative(md + xs, 0, xs, xs);
        setBackgroundResource(R.drawable.sv_bg_rounded);
        LayoutInflater.from(context).inflate(R.layout.sv_banner, this, true);
        icon = findViewById(R.id.sv_banner_icon);
        text = findViewById(R.id.sv_banner_text);
        action = findViewById(R.id.sv_banner_action);
        // 出現或改變時讀出，不打斷正在讀的內容
        text.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        show(Kind.INFO, "", null, null);
    }

    /**
     * @param kind        種類
     * @param message     訊息
     * @param actionLabel 動作按鈕文字（動詞）；沒有動作時為 {@code null}
     * @param onAction    動作
     */
    public void show(Kind kind, CharSequence message, @Nullable CharSequence actionLabel,
            @Nullable View.OnClickListener onAction) {
        int container = ContextCompat.getColor(getContext(), kind.container);
        int onContainer = ContextCompat.getColor(getContext(), kind.onContainer);
        ViewCompat.setBackgroundTintList(this, ColorStateList.valueOf(container));
        icon.setImageResource(kind.icon);
        icon.setImageTintList(ColorStateList.valueOf(onContainer));
        text.setText(message);
        text.setTextColor(onContainer);
        boolean hasAction = actionLabel != null && onAction != null;
        action.setVisibility(hasAction ? VISIBLE : GONE);
        if (hasAction) {
            action.setText(actionLabel);
            action.setTextColor(onContainer);
            action.setOnClickListener(onAction);
        }
        int xs = getResources().getDimensionPixelSize(R.dimen.sv_space_xs);
        setPaddingRelative(getPaddingStart(), 0, getPaddingEnd(), hasAction ? xs : 0);
    }
}
