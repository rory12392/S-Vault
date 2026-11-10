package app.svault.android.ui;

import android.view.View;
import androidx.annotation.ColorRes;
import androidx.core.content.ContextCompat;
import app.svault.android.R;
import com.google.android.material.snackbar.Snackbar;

/**
 * 操作結果的短訊息（Toast；android 用 Snackbar）：info／success／error，3 秒自動消失；
 * 剪貼簿倒數之類要「顯示到結束為止」的訊息用 {@link #sticky}，附「關閉」按鈕。
 */
public final class Toasts {

    /** 種類。 */
    public enum Kind {
        /** 預設的反色底（M3 inverse surface，已接到 fg／bg）。 */
        INFO(0, 0),
        SUCCESS(R.color.sv_success_container, R.color.sv_on_success_container),
        ERROR(R.color.sv_danger_container, R.color.sv_on_danger_container);

        @ColorRes final int container;
        @ColorRes final int onContainer;

        Kind(int container, int onContainer) {
            this.container = container;
            this.onContainer = onContainer;
        }
    }

    /** 自動消失的時間。 */
    static final int DURATION_MS = 3000;

    private Toasts() {
    }

    /**
     * @param anchor  畫面中的任一 View（Snackbar 會找到最近的 CoordinatorLayout 或根部）
     * @param message 訊息
     * @param kind    種類
     * @return 已顯示的 Snackbar
     */
    public static Snackbar show(View anchor, CharSequence message, Kind kind) {
        Snackbar s = style(Snackbar.make(anchor, message, DURATION_MS), kind);
        s.show();
        return s;
    }

    /**
     * 顯示到呼叫 {@link Snackbar#dismiss()} 或使用者按「關閉」為止。
     *
     * @param anchor  畫面中的任一 View
     * @param message 訊息
     * @param kind    種類
     * @return 已顯示的 Snackbar
     */
    public static Snackbar sticky(View anchor, CharSequence message, Kind kind) {
        Snackbar s = style(Snackbar.make(anchor, message, Snackbar.LENGTH_INDEFINITE), kind);
        s.setAction(R.string.sv_toast_close, v -> { });
        s.show();
        return s;
    }

    private static Snackbar style(Snackbar s, Kind kind) {
        if (kind.container != 0) {
            int on = ContextCompat.getColor(s.getContext(), kind.onContainer);
            s.setBackgroundTint(ContextCompat.getColor(s.getContext(), kind.container));
            s.setTextColor(on);
            s.setActionTextColor(on);
        }
        return s;
    }
}
