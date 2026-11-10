package app.svault.android.ui;

import android.content.Context;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import app.svault.android.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import java.util.List;

/**
 * 條目清單的一列：圖示＋名稱＋帳號，整列可點；「複製密碼」是獨立可點的 IconButton。
 * 放在 {@code RecyclerView} 中使用；搜尋結果時以 {@link #bind(String, String, String)} 的關鍵字標示符合的部分。
 */
public class EntryRow extends LinearLayout {

    private final TextView title;
    private final TextView username;
    /** 搜尋結果第二行「帳號 ・ 群組路徑」的分隔（與 desktop 的 EntryRow 相同）。 */
    private static final String SEPARATOR = " ・ ";

    private final MaterialButton copy;

    public EntryRow(Context context) {
        this(context, null);
    }

    public EntryRow(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.sv_row_min_height));
        int lg = getResources().getDimensionPixelSize(R.dimen.sv_space_lg);
        int xs = getResources().getDimensionPixelSize(R.dimen.sv_space_xs);
        setPaddingRelative(lg, 0, xs, 0);
        setClickable(true);
        setFocusable(true);
        setBackgroundResource(resolveResource(context, android.R.attr.selectableItemBackground));
        LayoutInflater.from(context).inflate(R.layout.sv_entry_row, this, true);
        title = findViewById(R.id.sv_entry_title);
        username = findViewById(R.id.sv_entry_username);
        copy = findViewById(R.id.sv_entry_copy);
    }

    /**
     * @param name     條目名稱
     * @param user     帳號；沒有時傳空字串
     * @param query    搜尋關鍵字；不是搜尋結果時傳 {@code null}
     */
    public void bind(String name, String user, @Nullable String query) {
        bind(name, user, query, null);
    }

    /**
     * 搜尋結果變體：第二行是「帳號 ・ 群組路徑」，只標示帳號中符合的部分
     * （群組名稱不在搜尋範圍，AC-03-1）。
     *
     * @param name      條目名稱
     * @param user      帳號；沒有時傳空字串
     * @param query     搜尋關鍵字；不是搜尋結果時傳 {@code null}
     * @param groupPath 群組路徑；不是搜尋結果時傳 {@code null}
     */
    public void bind(String name, String user, @Nullable String query, @Nullable String groupPath) {
        boolean noUser = user == null || user.isEmpty();
        title.setText(highlight(name, query));
        if (groupPath == null) {
            username.setText(highlight(user, query));
            username.setVisibility(noUser ? GONE : VISIBLE);
        } else {
            SpannableStringBuilder line = new SpannableStringBuilder();
            if (!noUser) {
                line.append(highlight(user, query)).append(SEPARATOR);
            }
            line.append(groupPath);
            username.setText(line);
            username.setVisibility(VISIBLE);
        }
        // TalkBack 把整列讀成「名稱，帳號 xxx」，複製鈕另外一個焦點並說出是哪一筆
        // 沒有帳號時只讀名稱，不讀出空的「帳號」（T-03 無障礙檢查發現）
        String spoken = noUser ? name : getResources().getString(R.string.sv_entry_accessible, name, user);
        setContentDescription(groupPath == null ? spoken
                : getResources().getString(R.string.sv_entry_accessible_group, spoken, groupPath));
        copy.setContentDescription(getResources().getString(R.string.sv_entry_copy_password_of, name));
    }

    /**
     * @param listener 按下「複製密碼」時；{@code null} 時隱藏按鈕（例如沒有密碼的條目）
     */
    public void setOnCopyPasswordListener(@Nullable View.OnClickListener listener) {
        copy.setOnClickListener(listener);
        copy.setVisibility(listener == null ? GONE : VISIBLE);
    }

    private CharSequence highlight(String text, @Nullable String query) {
        if (text == null) {
            return "";
        }
        List<Highlight.Range> ranges = Highlight.ranges(text, query);
        if (ranges.isEmpty()) {
            return text;
        }
        // 標示用 primary-container 底＋on-primary-container 字，兩種模式的對比度都 ≥ 9（design-impl §3）
        int bg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimaryContainer);
        int fg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimaryContainer);
        SpannableString s = new SpannableString(text);
        for (Highlight.Range r : ranges) {
            s.setSpan(new BackgroundColorSpan(bg), r.start(), r.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            s.setSpan(new ForegroundColorSpan(fg), r.start(), r.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    static int resolveResource(Context context, int attr) {
        TypedValue value = new TypedValue();
        context.getTheme().resolveAttribute(attr, value, true);
        return value.resourceId;
    }
}
