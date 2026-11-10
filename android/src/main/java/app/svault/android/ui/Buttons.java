package app.svault.android.ui;

import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import androidx.core.view.ViewCompat;
import app.svault.android.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.CircularProgressIndicatorSpec;
import com.google.android.material.progressindicator.IndeterminateDrawable;

/**
 * Button 的 loading 狀態：按鈕內 Spinner＋停用，文字不變（使用者知道按了什麼）。
 * 變體用 {@code Widget.SVault.Button.*} 樣式，不在這裡。
 */
public final class Buttons {

    private Buttons() {
    }

    /**
     * @param button  按鈕
     * @param loading 進行中時為 {@code true}
     */
    public static void setLoading(MaterialButton button, boolean loading) {
        boolean already = button.getTag(R.id.sv_tag_loading) != null;
        if (loading == already) {
            return;
        }
        if (loading) {
            // 保存原本的圖示與狀態，結束 loading 時還原
            button.setTag(R.id.sv_tag_loading, new Saved(button.getIcon(), button.isEnabled()));
            CircularProgressIndicatorSpec spec = new CircularProgressIndicatorSpec(button.getContext(), null);
            spec.indicatorSize = button.getResources().getDimensionPixelSize(R.dimen.sv_button_spinner);
            spec.trackThickness = button.getResources().getDimensionPixelSize(R.dimen.sv_button_spinner_track);
            spec.indicatorInset = 0;
            ColorStateList text = button.getTextColors();
            // 停用狀態的文字色，Spinner 和文字一致
            spec.indicatorColors = new int[] {text.getColorForState(new int[] {-android.R.attr.state_enabled},
                    text.getDefaultColor())};
            Drawable spinner = IndeterminateDrawable.createCircularDrawable(button.getContext(), spec);
            button.setEnabled(false);
            button.setIcon(spinner);
            ViewCompat.setStateDescription(button, button.getResources().getString(R.string.sv_busy));
        } else {
            Saved saved = (Saved) button.getTag(R.id.sv_tag_loading);
            button.setTag(R.id.sv_tag_loading, null);
            button.setIcon(saved.icon);
            button.setEnabled(saved.enabled);
            ViewCompat.setStateDescription(button, null);
        }
    }

    /** @return 按鈕是否在 loading 狀態 */
    public static boolean isLoading(MaterialButton button) {
        return button.getTag(R.id.sv_tag_loading) != null;
    }

    private record Saved(Drawable icon, boolean enabled) { }
}
