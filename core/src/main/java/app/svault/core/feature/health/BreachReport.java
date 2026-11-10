package app.svault.core.feature.health;

import java.util.List;

/**
 * 外洩檢查的結果（AC-13-3、AC-13-4）。和 {@link WeakReuseReport} 分開：外洩檢查失敗時弱／重複報告照常顯示。
 *
 * <p>只有 {@link Status#COMPLETED} 時 {@code hits} 才有意義；連線失敗或取消時一律是空清單，
 * 畫面不會把「只查了一半」誤顯示成「沒有外洩」。
 *
 * @param status          檢查結果
 * @param hits            密碼出現在外洩資料中的條目，依名稱排序（{@code EntryQuery} 的順序）
 * @param prefixesChecked 成功查詢的前綴數（日誌與摘要用）
 */
public record BreachReport(Status status, List<BreachHit> hits, int prefixesChecked) {

    /** 檢查結果。 */
    public enum Status {
        /** 全部前綴都查完，{@code hits} 是完整結果（空清單代表沒有發現外洩）。 */
        COMPLETED,
        /** 連線、逾時、伺服器錯誤或回應格式不對：畫面顯示「無法連線，稍後再試」（AC-13-4）。 */
        CONNECTION_FAILED,
        /** 執行緒被中斷（例如使用者離開頁面）：畫面不顯示結果。 */
        CANCELLED
    }

    /**
     * 複製成不可修改的清單；未完成時不接受命中。
     *
     * @throws IllegalArgumentException 狀態不是 {@link Status#COMPLETED} 卻帶了命中
     */
    public BreachReport {
        hits = List.copyOf(hits);
        if (status != Status.COMPLETED && !hits.isEmpty()) {
            throw new IllegalArgumentException("未完成的檢查不能有命中");
        }
    }

    /**
     * @return 檢查完成且沒有任何條目的密碼出現在外洩資料中
     */
    public boolean isClean() {
        return status == Status.COMPLETED && hits.isEmpty();
    }

    /**
     * 只輸出狀態與數量，不輸出任何條目資訊（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "BreachReport[" + status + "、前綴 " + prefixesChecked + "、命中 " + hits.size() + "]";
    }
}
