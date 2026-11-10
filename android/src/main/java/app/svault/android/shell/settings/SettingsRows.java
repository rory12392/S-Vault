package app.svault.android.shell.settings;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.IdRes;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import app.svault.android.R;

/**
 * 設定與關於頁的區塊標題與 SettingsRow（{@code settings_row.xml}）。
 */
final class SettingsRows {

    private SettingsRows() {
    }

    /**
     * @param parent 加在這裡的最後
     * @param title  區塊標題
     * @return 標題（TalkBack 標為標題）
     */
    static TextView header(ViewGroup parent, CharSequence title) {
        TextView header = (TextView) LayoutInflater.from(parent.getContext())
                .inflate(R.layout.settings_section_header, parent, false);
        header.setText(title);
        ViewCompat.setAccessibilityHeading(header, true);
        parent.addView(header);
        return header;
    }

    /**
     * @param parent      加在這裡的最後
     * @param id          列的 id（{@code View.NO_ID} 表示不設）
     * @param title       標題
     * @param description 說明；沒有時 {@code null}
     * @param navigation  開另一個畫面（右側顯示箭頭）
     * @param onClick     點列時
     * @return 列
     */
    static View row(ViewGroup parent, @IdRes int id, CharSequence title, @Nullable CharSequence description,
            boolean navigation, View.OnClickListener onClick) {
        View row = LayoutInflater.from(parent.getContext()).inflate(R.layout.settings_row, parent, false);
        if (id != View.NO_ID) {
            row.setId(id);
        }
        ((TextView) row.findViewById(R.id.settings_row_title)).setText(title);
        TextView desc = row.findViewById(R.id.settings_row_description);
        if (description != null) {
            desc.setText(description);
            desc.setVisibility(View.VISIBLE);
        }
        row.findViewById(R.id.settings_row_chevron).setVisibility(navigation ? View.VISIBLE : View.GONE);
        row.setOnClickListener(onClick);
        parent.addView(row);
        return row;
    }

    /**
     * @param row   {@link #row} 建立的列
     * @param value 目前的值（顯示在說明下方）
     */
    static void value(View row, CharSequence value) {
        TextView v = row.findViewById(R.id.settings_row_value);
        v.setText(value);
        v.setVisibility(View.VISIBLE);
    }

    /**
     * @param row {@link #row} 建立的列
     * @return 顯示中的值
     */
    static CharSequence value(View row) {
        return ((TextView) row.findViewById(R.id.settings_row_value)).getText();
    }
}
