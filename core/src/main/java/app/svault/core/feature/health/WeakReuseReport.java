package app.svault.core.feature.health;

import app.svault.core.vault.EntryView;
import java.util.List;

/**
 * 健檢報告的弱密碼與重複密碼部分（AC-13-1）。外洩檢查的結果另由 T-51 提供，不放在這裡：
 * 外洩檢查失敗時這份報告照常顯示（AC-13-4）。
 *
 * <p>是分析當下的快照；保管庫修改後由畫面重新分析一次。一筆條目可以同時出現在兩個清單。
 *
 * @param weak   強度評為弱的條目，依名稱排序
 * @param reused 共用同一密碼的各組條目，依各組第一筆條目的名稱排序
 */
public record WeakReuseReport(List<EntryView> weak, List<ReuseGroup> reused) {

    /**
     * 複製成不可修改的清單。
     */
    public WeakReuseReport {
        weak = List.copyOf(weak);
        reused = List.copyOf(reused);
    }

    /**
     * @return 重複使用密碼的條目總數（摘要的「重複使用 n」）
     */
    public int reusedEntryCount() {
        int count = 0;
        for (ReuseGroup g : reused) {
            count += g.entries().size();
        }
        return count;
    }

    /**
     * @return 沒有弱密碼也沒有重複密碼（畫面顯示空狀態）
     */
    public boolean isEmpty() {
        return weak.isEmpty() && reused.isEmpty();
    }

    /**
     * 只輸出數量，不輸出任何條目資訊（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "WeakReuseReport[弱 " + weak.size() + "、重複 " + reused.size() + " 組]";
    }
}
