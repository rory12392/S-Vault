package app.svault.android.shell.entries;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import app.svault.android.R;
import app.svault.core.shell.Texts;
import app.svault.core.sync.ConflictView;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 衝突對照的欄位名稱與值的說法（同 desktop 的 {@code conflict.field.*}、{@code conflict.value.*}）。
 * 對應表是純 Java，JVM 單元測試確認每一種都有文字（{@code ConflictTextsTest}）。
 */
final class ConflictTexts {

    private ConflictTexts() {
    }

    /**
     * @param kind 欄位種類
     * @return 欄位名稱的字串（自訂欄位與附件帶一個參數：名稱）
     */
    @StringRes
    static int field(ConflictView.Kind kind) {
        switch (kind) {
            case TITLE:
                return R.string.conflict_field_title;
            case USERNAME:
                return R.string.conflict_field_username;
            case PASSWORD:
                return R.string.conflict_field_password;
            case URL:
                return R.string.conflict_field_url;
            case NOTES:
                return R.string.conflict_field_notes;
            case CUSTOM:
                return R.string.conflict_field_custom;
            case GROUP:
                return R.string.conflict_field_group;
            case TAGS:
                return R.string.conflict_field_tags;
            case ATTACHMENT:
                return R.string.conflict_field_attachment;
            case ICON:
                return R.string.conflict_field_icon;
            default:
                return R.string.conflict_field_expiry;
        }
    }

    /**
     * @param form 值的說法
     * @return 說法的字串（帶一個參數的：圖示編號、附件雜湊、到期時間）；值本身直接顯示（文字、群組路徑、空白）時為 0
     */
    @StringRes
    static int value(ConflictView.Shown.Form form) {
        switch (form) {
            case REMOVED:
                return R.string.conflict_value_removed;
            case UNKNOWN_GROUP:
                return R.string.conflict_value_unknown_group;
            case ICON:
                return R.string.conflict_value_icon;
            case CUSTOM_ICON:
                return R.string.conflict_value_custom_icon;
            case ATTACHMENT:
                return R.string.conflict_value_attachment;
            case NEVER_EXPIRES:
                return R.string.conflict_value_never_expires;
            case EXPIRES:
                return R.string.conflict_value_expires;
            default:
                // TEXT、GROUP：值本身；EMPTY：空字串，DiffRow 顯示「（空白）」
                return 0;
        }
    }

    /**
     * @param form 值的說法
     * @return 在要遮罩的欄位裡要遮罩：只有值本身，「（已刪除）」等說法照常顯示
     */
    static boolean maskable(ConflictView.Shown.Form form) {
        return form == ConflictView.Shown.Form.TEXT;
    }

    /**
     * @param c   文字資源
     * @param row 衝突的欄位
     * @return 欄位名稱（「密碼」「自訂欄位「PIN」」）
     */
    static String fieldName(Context c, ConflictView.Row row) {
        return c.getString(field(row.kind()), row.name() == null ? "" : row.name());
    }

    /**
     * @param c     文字資源
     * @param value 一邊的值
     * @param zone  到期時間的時區
     * @return 要顯示的值；空白時空字串
     */
    static String shown(Context c, ConflictView.Shown value, ZoneId zone) {
        int res = value(value.form());
        if (res == 0) {
            return value.text() == null ? "" : value.text();
        }
        String arg = value.form() == ConflictView.Shown.Form.EXPIRES
                ? Texts.MINUTES.format(value.time().atZone(zone)) : value.text();
        return c.getString(res, arg == null ? "" : arg);
    }

    /**
     * @param c    文字資源
     * @param time 修改時間
     * @param zone 時區
     * @return 「修改於 yyyy/MM/dd HH:mm」；沒有時間時 {@code null}
     */
    @Nullable
    static String time(Context c, @Nullable Instant time, ZoneId zone) {
        return time == null ? null : c.getString(R.string.conflict_time, Texts.MINUTES.format(time.atZone(zone)));
    }
}
