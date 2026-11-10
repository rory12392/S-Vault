package app.svault.core.session;

import app.svault.core.vault.KdfCost;
import app.svault.core.vault.VaultException;

/**
 * 「升級加密設定」按鈕的結果（L-4，D-187）：以最後開啟中的保管庫是否已經不偏弱為準。遠端剛好被另一台改過時，
 * 合併寫回的是遠端那份的參數，升級沒有生效；只看「沒有衝突」會誤報成功（Codex M-04 審查 B3）。
 */
public final class KdfUpgrade {

    private KdfUpgrade() {
    }

    /**
     * 在保管庫執行緒呼叫。
     *
     * @param session 解鎖中的 session
     * @return 升級生效（最後的保管庫已經不偏弱）；遠端有變更而沒生效時為 {@code false}，下次解鎖會再提示
     * @throws VaultException 存檔失敗
     */
    public static boolean run(VaultSession session) throws VaultException {
        session.upgradeKdf();
        return session.isUnlocked() && !KdfCost.isWeak(session.vault().format());
    }
}
