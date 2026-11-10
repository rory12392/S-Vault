package app.svault.android.ui;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;

/**
 * Snackbar 顯示期間讓出空間（T-109）：最大字體加上鍵盤時，畫面下方能用的高度很小，Snackbar 會蓋住按鈕（「已上鎖」蓋住
 * 「解鎖」、「已複製」蓋住「複製」）。顯示期間給捲動區多一段和 Snackbar 一樣高的底部空間，並把指定的元件捲到 Snackbar
 * 上方；鍵盤稍後才彈出、Snackbar 跟著移動時重新計算。Snackbar 消失後恢復原本的底部空間（已捲過的位置不動）。
 */
public final class SnackbarRoom {

    private SnackbarRoom() {
    }

    /**
     * @param snackbar 剛 {@code show()} 的 Snackbar
     * @param scroll   要讓出空間的捲動區（{@code NestedScrollView} 或 {@code ScrollView}）
     * @param target   要保持在 Snackbar 上方的元件（通常是最下面的按鈕）
     * @return 同一個 Snackbar
     */
    public static Snackbar keepClear(Snackbar snackbar, ViewGroup scroll, View target) {
        int basePadding = scroll.getPaddingBottom();
        View bar = snackbar.getView();
        ViewTreeObserver.OnGlobalLayoutListener follow = () -> {
            int height = bar.getHeight() + bottomMargin(bar);
            if (scroll.getPaddingBottom() != basePadding + height) {
                scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(), scroll.getPaddingRight(),
                        basePadding + height);
            }
            int[] t = new int[2];
            int[] b = new int[2];
            target.getLocationInWindow(t);
            bar.getLocationInWindow(b);
            int overlap = t[1] + target.getHeight() - b[1];
            if (overlap > 0 && bar.isShown()) {
                scroll.scrollBy(0, overlap);
            }
        };
        bar.getViewTreeObserver().addOnGlobalLayoutListener(follow);
        snackbar.addCallback(new BaseTransientBottomBar.BaseCallback<Snackbar>() {
            // 滑入動畫期間量到的位置偏低（含 translation），動畫結束時再算一次
            @Override
            public void onShown(Snackbar transientBottomBar) {
                follow.onGlobalLayout();
            }

            @Override
            public void onDismissed(Snackbar transientBottomBar, int event) {
                bar.getViewTreeObserver().removeOnGlobalLayoutListener(follow);
                scroll.setPadding(scroll.getPaddingLeft(), scroll.getPaddingTop(), scroll.getPaddingRight(),
                        basePadding);
            }
        });
        return snackbar;
    }

    private static int bottomMargin(View bar) {
        ViewGroup.LayoutParams lp = bar.getLayoutParams();
        return lp instanceof ViewGroup.MarginLayoutParams m ? m.bottomMargin : 0;
    }
}
