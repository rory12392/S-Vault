package app.svault.android.shell.entries;

import android.view.View;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.R;
import app.svault.core.shell.Texts;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 條目畫面共用的小工具。
 */
final class Pages {

    /** 所有時間以裝置時區、yyyy/MM/dd HH:mm 顯示。 */
    private static final DateTimeFormatter TIME = Texts.MINUTES;

    private Pages() {
    }

    /**
     * 清單底部留出 FAB 的高度，最後一列（和它的複製鈕）不會被 FAB 蓋住。FAB 的高度依字體大小而變，所以排版後再算，
     * 不寫死尺寸（DESIGN §2.5）。
     *
     * @param list 清單
     * @param fab  右下的「新增條目」
     */
    static void padForFab(RecyclerView list, ExtendedFloatingActionButton fab) {
        int gap = list.getResources().getDimensionPixelSize(R.dimen.sv_space_lg);
        fab.addOnLayoutChangeListener((View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) -> {
            int bottom = v.getVisibility() == View.VISIBLE ? (b - t) + 2 * gap : 0;
            if (list.getPaddingBottom() != bottom) {
                list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), bottom);
            }
        });
    }

    /**
     * @param time 時間
     * @return 「yyyy/MM/dd HH:mm」（裝置時區）
     */
    static String time(Instant time) {
        return TIME.format(time.atZone(ZoneId.systemDefault()));
    }
}
