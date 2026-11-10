package app.svault.core.sync;

import app.svault.core.vault.EntrySnapshot;
import app.svault.core.vault.GroupSnapshot;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * 判斷遠端是不是從基準延伸出來的（Architecture §4 第 4 點；AC-07-9，T-27）。
 *
 * <p>任何 KDBX 工具（S-Vault、KeePassXC、KeePassDX）開啟基準那一份、修改後存檔，結果對基準裡的每個條目與群組都滿足：
 * <ul>
 *   <li>不在了 → 記在 {@code DeletedObjects}（永久刪除會寫進去；移進回收桶的還在）；</li>
 *   <li>條目內容和基準不同 → 基準那個版本在它的歷史紀錄裡（修改前的版本會推入 History；以修改時間比對）。</li>
 * </ul>
 * 遠端被整份取代時（Google Drive 恢復同步時以另一台離線期間的版本覆蓋雲端；T-17 實測）這兩條至少一條不成立：這台寫上去的
 * 新增「不見了、也沒有刪除紀錄」，這台改過的條目「內容不同、歷史裡也沒有這台的版本」。還原舊備份也長這樣，所以還原時
 * 會先把被移除的寫進 {@code DeletedObjects}、被換掉的版本推入歷史紀錄（{@code core.vault.RestoreMarks}），還原的結果因此
 * 仍是延伸，刪除照常生效。
 *
 * <p>只看內容與歷史、不比較兩台裝置的時鐘（T-27：時間比較在時鐘不一致時會誤判）。群組沒有歷史紀錄，只看第一條。
 * 誤判的方向：其他工具刪除但沒寫 {@code DeletedObjects}、或檔案關掉了歷史紀錄（{@code HistoryMaxItems}＝0）時，會被當成
 * 「被取代」，合併改用沒有基準的規則（只在一邊的保留、同一筆以較新的為準、舊的進歷史紀錄）：刪除沒有生效，但不會遺失資料。
 */
final class RemoteLineage {

    private RemoteLineage() {
    }

    /**
     * @param base   上次同步成功的版本（同一個保管庫）
     * @param remote 遠端目前的內容
     * @return 遠端是從基準延伸出來的（可以照常三方合併）
     */
    static boolean descendsFrom(VaultSnapshot base, VaultSnapshot remote) {
        Map<UUID, Instant> deleted = remote.deleted();
        for (EntrySnapshot b : base.entries().values()) {
            EntrySnapshot r = remote.entries().get(b.uuid());
            if (r == null ? !deleted.containsKey(b.uuid()) : !containsVersion(r, b)) {
                return false;
            }
        }
        for (GroupSnapshot b : base.groups().values()) {
            if (!remote.groups().containsKey(b.uuid()) && !deleted.containsKey(b.uuid())) {
                return false;
            }
        }
        return true;
    }

    /** @return {@code r} 就是 {@code b} 的內容，或 {@code b} 那個版本在 {@code r} 的歷史紀錄裡 */
    private static boolean containsVersion(EntrySnapshot r, EntrySnapshot b) {
        return r.fields().equals(b.fields()) || r.lastModified().equals(b.lastModified())
                || r.historyTimes().contains(b.lastModified());
    }
}
