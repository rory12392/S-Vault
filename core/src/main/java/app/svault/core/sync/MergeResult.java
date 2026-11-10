package app.svault.core.sync;

import app.svault.core.vault.MergePlan;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 三方合併的結果（Architecture §4）：要套用到遠端的變更（{@link #plan()}）、衝突清單與通知。
 *
 * <p>有衝突時 {@link #plan()} 不能用（AC-08-3：不寫遠端）；使用者在對照畫面選完後以 {@link #resolve} 得到沒有衝突的
 * 新結果。結果本身不可變。
 */
public final class MergeResult {

    private final MergePlan plan;
    private final List<Conflict> conflicts;
    private final List<MergeNotice> notices;
    private final int changedEntries;
    private final VaultSnapshot base;
    private final VaultSnapshot local;
    private final VaultSnapshot remote;

    MergeResult(MergePlan plan, List<Conflict> conflicts, List<MergeNotice> notices, int changedEntries,
                VaultSnapshot base, VaultSnapshot local, VaultSnapshot remote) {
        this.plan = plan;
        this.conflicts = List.copyOf(conflicts);
        this.notices = List.copyOf(notices);
        this.changedEntries = changedEntries;
        this.base = base;
        this.local = local;
        this.remote = remote;
    }

    /**
     * @return 有衝突要使用者選（AC-08-1）
     */
    public boolean hasConflicts() {
        return !conflicts.isEmpty();
    }

    /**
     * @return 衝突的條目（依條目在遠端／本機的順序）；沒有時為空清單
     */
    public List<Conflict> conflicts() {
        return conflicts;
    }

    /**
     * @return 要讓使用者知道的事（AC-07-4 等）
     */
    public List<MergeNotice> notices() {
        return notices;
    }

    /**
     * @return 合併後和這台裝置原本的內容不同的條目數（新增、修改、移除；AC-07-5 的「合併 n 筆」）
     */
    public int changedEntries() {
        return changedEntries;
    }

    /**
     * 要套用到遠端那份資料庫物件的變更（{@code MergeWriter.apply(遠端, 本機, plan)}）。
     *
     * @return 合併計畫
     * @throws IllegalStateException 還有衝突沒解決（先 {@link #resolve}）
     */
    public MergePlan plan() {
        if (hasConflicts()) {
            throw new IllegalStateException("還有 " + conflicts.size() + " 筆衝突沒有解決");
        }
        return plan;
    }

    /**
     * 套用使用者在對照畫面的選擇（AC-08-2）。衝突的條目修改時間設成 {@code resolvedAt}，沒被選的版本（和結果不同的
     * 那一邊或兩邊）推入該條目的歷史紀錄。
     *
     * @param choices    每筆衝突條目、每個衝突欄位選哪一邊（{@link Conflict#choose} 可整筆選同一邊）
     * @param resolvedAt 解決的時間（會截到秒）
     * @return 沒有衝突的新結果（同樣的通知；這個物件不變）
     * @throws IllegalArgumentException 有衝突的條目或欄位沒有選擇
     */
    public MergeResult resolve(Map<UUID, Map<String, Side>> choices, Instant resolvedAt) {
        Map<UUID, Map<String, Side>> copy = new HashMap<>();
        for (Conflict c : conflicts) {
            Map<String, Side> chosen = choices.get(c.entryId());
            for (Conflict.Field f : c.fields()) {
                if (chosen == null || chosen.get(f.key()) == null) {
                    throw new IllegalArgumentException("衝突的條目有欄位沒有選擇：" + c.entryId());
                }
            }
            copy.put(c.entryId(), new HashMap<>(chosen));
        }
        return ThreeWayMerger.merge(base, local, remote, copy, resolvedAt);
    }

    /** @return 合併時這台裝置的內容（{@link ConflictView} 找條目名稱與群組路徑用） */
    VaultSnapshot localSnapshot() {
        return local;
    }

    /** @return 合併時遠端的內容 */
    VaultSnapshot remoteSnapshot() {
        return remote;
    }

    @Override
    public String toString() {
        return "MergeResult[衝突 " + conflicts.size() + "、通知 " + notices.size() + "、變更 " + changedEntries + "]";
    }
}
