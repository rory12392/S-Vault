package app.svault.core.sync;

import app.svault.core.vault.MergePlan;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * 三方合併（Architecture §4；US-07、US-08）。純函式：輸入三份記憶體中的快照（基準 B、本機 L、遠端 R），輸出
 * {@link MergeResult}，不讀寫檔案、不需要金鑰。結果的 {@link MergePlan} 以 {@code MergeWriter.apply(遠端, 本機, plan)}
 * 套用在遠端那份資料庫物件上，S-Vault 看不懂的元素因此原樣保留（第 7 點）。
 *
 * <p>規則摘要（細節見 Architecture §4）：
 * <ol>
 *   <li>以 UUID 對齊條目與群組；</li>
 *   <li>逐欄合併：L＝B 取 R、R＝B 取 L、L＝R 取 L；三者都不同是衝突（AC-08-1），整筆條目進衝突清單，其他欄位照常合併；</li>
 *   <li>只在一邊、基準沒有的保留（AC-07-2）；</li>
 *   <li>「基準有、這一邊沒有」或「從回收桶外移進回收桶」是這一邊刪除：另一邊沒修改就刪除，有修改就保留修改並通知
 *       （AC-07-4）；刪除紀錄取聯集、不含被保留的；群組同理，裡面有要保留的東西時群組也保留。遠端不是從基準延伸出來的
 *       （被整份取代，{@link RemoteLineage}；AC-07-9）時基準不能用，改用第 6 點並通知 {@link MergeNotice.Kind#REMOTE_REPLACED}；</li>
 *   <li>歷史紀錄以修改時間聯集；衝突解決時沒被選的版本推入歷史紀錄（AC-08-2）；</li>
 *   <li>沒有基準時每筆以 {@code LastModificationTime} 較新的為準、較舊的推入歷史紀錄，所在群組看
 *       {@code LocationChanged} 較新的，不產生衝突（{@link MergeNotice.Kind#FIRST_SYNC}）。</li>
 * </ol>
 */
public final class ThreeWayMerger {

    private ThreeWayMerger() {
    }

    /**
     * 合併。
     *
     * @param base   上次同步成功的版本（{@code base/}）；沒有基準時傳 {@code null}，保管庫 ID 和另外兩份不同時也當成沒有
     * @param local  這台裝置目前的內容
     * @param remote 遠端檔案目前的內容
     * @return 合併結果（可能有衝突）
     * @throws IllegalArgumentException 本機與遠端不是同一個保管庫（根群組 UUID 不同）
     */
    public static MergeResult merge(VaultSnapshot base, VaultSnapshot local, VaultSnapshot remote) {
        return merge(base, local, remote, Collections.<UUID, Map<String, Side>>emptyMap(), null);
    }

    static MergeResult merge(VaultSnapshot base, VaultSnapshot local, VaultSnapshot remote,
                             Map<UUID, Map<String, Side>> choices, Instant resolvedAt) {
        if (!local.vaultId().equals(remote.vaultId())) {
            throw new IllegalArgumentException("本機與遠端不是同一個保管庫");
        }
        VaultSnapshot usableBase = base != null && base.vaultId().equals(remote.vaultId()) ? base : null;
        // 遠端不是從基準延伸出來的（被整份取代，AC-07-9）：基準不是兩邊的共同祖先，拿它判斷「誰刪了、誰改了」會把這台的
        // 新增當成遠端刪除、把這台的修改當成遠端改回舊值。改用沒有基準的規則（第 6 點），兩邊都保留
        boolean replaced = usableBase != null && !RemoteLineage.descendsFrom(usableBase, remote);
        if (replaced) {
            usableBase = null;
        }
        MergeState s = new MergeState(usableBase, local, remote, choices, resolvedAt);
        EntryMerge.mergeAll(s);
        GroupMerge.mergeAll(s);
        GroupMerge.rescue(s);
        GroupMerge.settle(s);
        MergePlan plan = PlanAssembler.build(s);
        int changed = PlanAssembler.changedForLocal(s);
        if (usableBase == null && (!plan.isEmpty() || changed > 0)) {
            s.notices.add(0, new MergeNotice(replaced ? MergeNotice.Kind.REMOTE_REPLACED : MergeNotice.Kind.FIRST_SYNC,
                    null));
        }
        return new MergeResult(plan, s.conflicts, s.notices, changed, usableBase, local, remote);
    }
}
