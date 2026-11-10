package app.svault.core.vault;

/**
 * 檔案的加密設定目前不支援（AC-01-7），例如 KDF 是 Argon2id（KeePassJava2 2.2.6 只支援 Argon2d，D-10）。
 * 判斷只讀未加密的外層標頭，不需要主密碼，也不會改動檔案。
 */
public final class UnsupportedKdfException extends VaultException {

    private static final long serialVersionUID = 1L;

    /** {@link #setting()} 的值：KDF 參數超過上限（記憶體、迭代、平行度或 AES 輪數；資安總檢 L-1）。 */
    public static final String TOO_COSTLY = "金鑰衍生參數過大";

    private final String setting;

    /**
     * @param setting 不支援的設定名稱，直接放進使用者看到的訊息，例如 {@code Argon2id}、{@code Twofish}
     */
    public UnsupportedKdfException(String setting) {
        super("不支援的加密設定：" + setting);
        this.setting = setting;
    }

    /**
     * @return 不支援的設定名稱，例如 {@code Argon2id}
     */
    public String setting() {
        return setting;
    }
}
