package app.svault.core.sync;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次同步（存檔、解鎖後或回到前景的更新、自動重試、衝突解決）的結果，給畫面決定要顯示什麼（同步
 * Banner；DESIGN §6）。不含保管庫內容。
 *
 * @param status         結果種類
 * @param changedEntries 合併後和這台裝置原本的內容不同的條目數（AC-07-5 的「合併 n 筆」）；沒有合併時 0
 * @param notices        合併時要讓使用者知道的事（AC-07-4 等）；沒有合併時為空
 * @param conflicts      衝突的條目數（{@link Status#CONFLICT} 時大於 0）
 */
public record SyncReport(Status status, int changedEntries, List<MergeNotice> notices, int conflicts) {

    /** 結果種類。 */
    public enum Status {
        /** 遠端和上次同步時相同，這台也沒有要寫的：什麼都沒做。 */
        UP_TO_DATE,
        /** 遠端沒有變更，已把這台的修改寫進去。 */
        SAVED,
        /** 遠端有變更，已合併（有要寫的就已寫回），內容換成合併後的版本（AC-07-1、AC-07-5）。 */
        MERGED,
        /** 有衝突要使用者選（AC-08-1）：沒有寫遠端（AC-08-3），這台的修改留在 {@code pending/}。 */
        CONFLICT
    }

    /**
     * 複製成不可修改的清單。
     */
    public SyncReport {
        Objects.requireNonNull(status, "status");
        notices = List.copyOf(notices);
    }

    /**
     * @param status 沒有合併的結果種類
     * @return 不含合併資訊的結果
     */
    static SyncReport of(Status status) {
        return new SyncReport(status, 0, List.<MergeNotice>of(), 0);
    }

    /**
     * @param result 合併結果
     * @return {@link Status#MERGED} 或 {@link Status#CONFLICT}
     */
    static SyncReport of(MergeResult result) {
        return new SyncReport(result.hasConflicts() ? Status.CONFLICT : Status.MERGED, result.changedEntries(),
                result.notices(), result.conflicts().size());
    }

    /** @return 有衝突沒解決 */
    public boolean hasConflicts() {
        return status == Status.CONFLICT;
    }

    /**
     * 同一種通知合成一則（「n 筆條目在另一台裝置被刪除…」）；文字由平台的字串資源產生。
     *
     * @return 每種通知的筆數，依 {@link MergeNotice.Kind} 的順序
     */
    public Map<MergeNotice.Kind, Integer> noticeCounts() {
        Map<MergeNotice.Kind, Integer> counts = new EnumMap<>(MergeNotice.Kind.class);
        for (MergeNotice n : notices) {
            counts.merge(n.kind(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
}
