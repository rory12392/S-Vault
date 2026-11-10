package app.svault.android.feature.biometric;

import java.util.Set;

/**
 * 加密後的主密碼（密文與 IV）的儲存處。正式實作是 {@link PrefsBioSecretStore}（私有偏好）。
 * 這裡只會有 Keystore 金鑰加密後的資料，主密碼明文永遠不經過本介面（AC-11-2）。
 */
interface BioSecretStore {

    /** 加密後的主密碼。密文與 IV 不是機密，但仍不需要 {@code toString}，所以用類別而不是 record。 */
    final class Sealed {
        private final byte[] iv;
        private final byte[] ciphertext;

        Sealed(byte[] iv, byte[] ciphertext) {
            this.iv = iv.clone();
            this.ciphertext = ciphertext.clone();
        }

        byte[] iv() {
            return iv.clone();
        }

        byte[] ciphertext() {
            return ciphertext.clone();
        }
    }

    /**
     * @param vaultId 保管庫 ID
     * @param sealed  密文與 IV（取代既有的）
     */
    void put(String vaultId, Sealed sealed);

    /**
     * @param vaultId 保管庫 ID
     * @return 密文與 IV；沒有時為 {@code null}
     * @throws BioException 存的資料格式不對（{@link BioReason#CORRUPT_DATA}）
     */
    Sealed get(String vaultId) throws BioException;

    /** @param vaultId 保管庫 ID；不存在時不做事 */
    void remove(String vaultId);

    /** @return 有存資料的保管庫 ID */
    Set<String> vaultIds();
}
