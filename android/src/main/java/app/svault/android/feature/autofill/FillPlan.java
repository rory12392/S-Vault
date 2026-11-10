package app.svault.android.feature.autofill;

import app.svault.core.vault.EntryView;
import java.util.List;

/**
 * 一次自動填入請求的處理結果（{@link FillPlanner}），由服務轉成 {@code FillResponse}。
 *
 * <p>{@code toString()} 不會印出條目內容：{@link EntryView#toString()} 只輸出 UUID。
 *
 * @param outcome 結果種類
 * @param fields  要填的欄位
 * @param entries 要提供的條目（只有 {@link Outcome#ENTRIES} 時有，最多 10 筆）
 * @param <I>     欄位代號的型別
 */
record FillPlan<I>(Outcome outcome, FieldScan<I> fields, List<EntryView> entries) {

    /** 結果種類。 */
    enum Outcome {
        /** 不提供任何建議（停用、沒有登入欄位、S-Vault 自己的畫面）。 */
        NONE,
        /** 保管庫上鎖：回傳需要驗證的項目（T-81），選了之後先解鎖再比對。 */
        LOCKED,
        /**
         * 沒有符合的條目，或瀏覽器拿不到網域：只提供「搜尋保管庫」，不給任何帳密（AC-10-3、AC-10-5）；使用者在條目選擇頁
         * 挑了不符的條目要先確認警告（T-83）。
         */
        SEARCH_ONLY,
        /** 有符合的條目。 */
        ENTRIES
    }

    FillPlan {
        entries = List.copyOf(entries);
    }

    static <I> FillPlan<I> of(Outcome outcome, FieldScan<I> fields) {
        return new FillPlan<>(outcome, fields, List.of());
    }

    /** @return 有符合的條目 */
    boolean matched() {
        return outcome == Outcome.ENTRIES;
    }
}
