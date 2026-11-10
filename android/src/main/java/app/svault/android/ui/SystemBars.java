package app.svault.android.ui;

import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * edge-to-edge（targetSdk 35 起強制）：內容畫到系統列底下，由根部以內距避開狀態列、導覽列、瀏海與鍵盤。
 */
public final class SystemBars {

    private SystemBars() {
    }

    /**
     * 讓 {@code root} 的內距＝原本的內距＋系統列的範圍。根部的背景仍畫滿整個畫面（狀態列底下是 bg 色）。
     *
     * @param root 畫面的根部
     */
    public static void applyInsets(View root) {
        int left = root.getPaddingLeft();
        int top = root.getPaddingTop();
        int right = root.getPaddingRight();
        int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }
}
