package app.svault.core.feature.health;

import app.svault.core.vault.EntryView;

/**
 * 外洩檢查的一筆命中（AC-13-3）：這筆條目的密碼出現在外洩資料中。只保留條目快照與次數，
 * 不保留密碼的雜湊。
 *
 * @param entry 條目（畫面用 {@code title()} 顯示、{@code uuid()} 跳到條目）
 * @param count 在 Have I Been Pwned 資料中出現的次數，至少 1
 */
public record BreachHit(EntryView entry, long count) {

    /**
     * @throws IllegalArgumentException 次數小於 1（次數 0 是回應填充，不是命中）
     */
    public BreachHit {
        if (count < 1) {
            throw new IllegalArgumentException("命中次數至少為 1");
        }
    }

    /**
     * 只輸出次數：條目的任何內容都不輸出（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "BreachHit[" + count + " 次]";
    }
}
