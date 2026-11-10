package app.svault.android.shell.entries;

import android.content.Context;
import androidx.annotation.StringRes;
import app.svault.android.R;
import app.svault.core.sync.MergeNotice;
import app.svault.core.sync.SyncReport;
import java.util.Map;

/**
 * 合併後的提示文字（AC-07-5、AC-07-4）。核心只給通知的種類（{@link MergeNotice.Kind}），文字在字串資源；
 * Snackbar 一次只顯示一則，所以「已更新為最新資料（合併 n 筆）」與各種通知合成同一則、分行顯示。
 */
final class SyncTexts {

    /** Snackbar 最多顯示的行數（合併筆數＋最多幾種通知）。 */
    static final int MAX_LINES = 5;

    private SyncTexts() {
    }

    /**
     * @param context 字串資源
     * @param report  合併的結果
     * @return 「已更新為最新資料（合併 n 筆）」＋每種通知一行
     */
    static String merged(Context context, SyncReport report) {
        StringBuilder out = new StringBuilder(context.getString(R.string.entries_merged, report.changedEntries()));
        for (Map.Entry<MergeNotice.Kind, Integer> n : report.noticeCounts().entrySet()) {
            int count = n.getValue();
            out.append('\n').append(context.getString(count == 1 ? one(n.getKey()) : many(n.getKey()), count));
        }
        return out.toString();
    }

    @StringRes
    private static int one(MergeNotice.Kind kind) {
        switch (kind) {
            case KEPT_LOCAL_EDIT:
                return R.string.entries_notice_kept_local_one;
            case KEPT_REMOTE_EDIT:
                return R.string.entries_notice_kept_remote_one;
            case GROUP_KEPT:
                return R.string.entries_notice_group_kept_one;
            case MOVED_TO_ROOT:
                return R.string.entries_notice_moved_to_root_one;
            case GROUP_NEWER_KEPT:
                return R.string.entries_notice_group_newer_one;
            case REMOTE_REPLACED:
                return R.string.entries_notice_remote_replaced;
            case DELETED_BY_RECORD:
                return R.string.entries_notice_deleted_by_record_one;
            default:
                return R.string.entries_notice_first_sync;
        }
    }

    @StringRes
    private static int many(MergeNotice.Kind kind) {
        switch (kind) {
            case KEPT_LOCAL_EDIT:
                return R.string.entries_notice_kept_local_many;
            case KEPT_REMOTE_EDIT:
                return R.string.entries_notice_kept_remote_many;
            case GROUP_KEPT:
                return R.string.entries_notice_group_kept_many;
            case MOVED_TO_ROOT:
                return R.string.entries_notice_moved_to_root_many;
            case GROUP_NEWER_KEPT:
                return R.string.entries_notice_group_newer_many;
            case REMOTE_REPLACED:
                return R.string.entries_notice_remote_replaced;
            case DELETED_BY_RECORD:
                return R.string.entries_notice_deleted_by_record_many;
            default:
                return R.string.entries_notice_first_sync;
        }
    }
}
