package app.svault.core.vault;

/**
 * 金鑰衍生的成本超過「先問使用者」的門檻（{@link KdfCost#CONFIRM_FACTOR}；C-4，D-186）。在跑 KDF 之前丟出：
 * KDF 在驗證主密碼與 HMAC 之前執行，能改雲端檔案標頭的人不需要知道主密碼，就能讓解鎖卡很久。畫面顯示預估時間，
 * 使用者確認後以 {@link VaultCodec#open(byte[], byte[], boolean)} 重新開啟。
 */
public final class CostlyKdfException extends VaultException {

    private static final long serialVersionUID = 1L;

    private final double factor;

    /**
     * @param factor 成本是 S-Vault 預設（AC-01-8）的幾倍
     */
    public CostlyKdfException(double factor) {
        super("金鑰衍生成本是預設的 " + Math.round(factor) + " 倍");
        this.factor = factor;
    }

    /** @return 成本是 S-Vault 預設的幾倍 */
    public double factor() {
        return factor;
    }
}
