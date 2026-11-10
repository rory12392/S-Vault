package app.svault.android.feature.autofill;

import android.content.res.ColorStateList;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.ColorInt;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;
import app.svault.android.R;
import com.google.android.material.color.MaterialColors;

/**
 * 把目前的狀態填進 {@code af_status.xml}（T-82；設定段落與引導頁共用）。已啟用＝success 色＋勾、未啟用＝一般文字＋資訊圖示、
 * 不支援＝warning 圖示，圖示與文字一起表達，不只靠顏色（DESIGN §2）。
 */
final class AfStatusView {

    private AfStatusView() {
    }

    /**
     * @param root    含 {@code af_status} 的畫面
     * @param service 系統目前的狀態
     */
    static void bind(View root, SetupState.Service service) {
        ImageView icon = root.findViewById(R.id.af_status_icon);
        TextView text = root.findViewById(R.id.af_status_text);
        TextView note = root.findViewById(R.id.af_status_note);
        @DrawableRes int iconRes;
        @StringRes int textRes;
        @StringRes int noteRes;
        @ColorInt int iconColor;
        @ColorInt int textColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurface);
        switch (service) {
            case ENABLED -> {
                iconRes = R.drawable.sv_ic_check_circle;
                textRes = R.string.af_status_enabled;
                noteRes = R.string.af_status_enabled_note;
                iconColor = ContextCompat.getColor(root.getContext(), R.color.sv_success);
                textColor = iconColor;
            }
            case NOT_ENABLED -> {
                iconRes = R.drawable.sv_ic_info;
                textRes = R.string.af_status_off;
                noteRes = R.string.af_status_off_note;
                iconColor = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOnSurfaceVariant);
            }
            default -> {
                iconRes = R.drawable.sv_ic_warning;
                textRes = R.string.af_status_unsupported;
                noteRes = R.string.af_status_unsupported_note;
                iconColor = ContextCompat.getColor(root.getContext(), R.color.sv_warning);
            }
        }
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(iconColor));
        text.setText(textRes);
        text.setTextColor(textColor);
        note.setText(noteRes);
    }
}
