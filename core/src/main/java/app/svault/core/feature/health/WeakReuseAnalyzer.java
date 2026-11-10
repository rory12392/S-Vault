package app.svault.core.feature.health;

import app.svault.core.security.PasswordStrength;
import app.svault.core.security.StrengthEstimator;
import app.svault.core.vault.EntryQuery;
import app.svault.core.vault.EntryView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 健檢的弱密碼與重複密碼分析（AC-13-1）。
 *
 * <ul>
 *   <li>弱密碼：{@link StrengthEstimator} 評為 {@link PasswordStrength#WEAK}；條目的名稱與帳號當上下文，
 *       密碼和它們相近時也算弱。</li>
 *   <li>重複：完全相同（區分大小寫）的密碼出現在 2 筆以上。</li>
 *   <li>只看 {@link EntryQuery#all()}：資源回收桶裡的條目不列入，也不和其他條目算成重複。</li>
 *   <li>沒有密碼的條目（例如只放備註）兩項都不列：沒有可以更換的密碼，列出來只是雜訊。</li>
 * </ul>
 *
 * <p>只讀取、不修改保管庫，不連網。同步執行：2,000 筆約數秒（強度評估佔大部分），平台外殼放到背景執行緒
 * 並顯示 ProgressOverlay。可以從不同執行緒呼叫（{@link StrengthEstimator} 本身是同步的）。
 */
public final class WeakReuseAnalyzer {

    private final StrengthEstimator estimator;

    /**
     * @param estimator 強度評估；建立時要載入字典，呼叫端傳入共用的實例
     */
    public WeakReuseAnalyzer(StrengthEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * @param query 條目查詢（分析當下的快照）
     * @return 弱密碼與重複密碼；沒有問題時 {@link WeakReuseReport#isEmpty()} 為 true
     * @throws NullPointerException query 為 null
     */
    public WeakReuseReport analyze(EntryQuery query) {
        return analyze(query.all());
    }

    /**
     * 給平台外殼用（T-52）：模組從 {@code ModuleHost.unlockedEntries()} 拿到的是 {@code all()} 的清單，不是
     * {@link EntryQuery}。呼叫端只傳不含資源回收桶的清單（{@code all()} 本來就不含）。
     *
     * @param entries 要分析的條目（分析當下的快照，依名稱排序）
     * @return 弱密碼與重複密碼
     * @throws NullPointerException entries 為 null
     */
    public WeakReuseReport analyze(List<EntryView> entries) {
        List<EntryView> weak = new ArrayList<>();
        // 規格要求重複比較只在記憶體中進行、不另存雜湊：以密碼字串本身當鍵，分析結束後整個表就丟掉。
        // 字串本來就在 EntryView 裡，這裡不多產生副本。LinkedHashMap 讓各組依第一次出現（名稱）的順序排列。
        Map<String, List<EntryView>> byPassword = new LinkedHashMap<>();
        for (EntryView e : entries) {
            String password = e.password();
            if (password.isEmpty()) {
                continue;
            }
            if (estimator.estimate(password, List.of(e.title(), e.username())) == PasswordStrength.WEAK) {
                weak.add(e);
            }
            byPassword.computeIfAbsent(password, k -> new ArrayList<>()).add(e);
        }
        List<ReuseGroup> reused = new ArrayList<>();
        for (List<EntryView> sharing : byPassword.values()) {
            if (sharing.size() >= 2) {
                reused.add(new ReuseGroup(sharing));
            }
        }
        return new WeakReuseReport(weak, reused);
    }
}
