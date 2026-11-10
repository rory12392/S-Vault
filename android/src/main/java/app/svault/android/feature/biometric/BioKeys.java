package app.svault.android.feature.biometric;

import java.util.Set;
import javax.crypto.Cipher;

/**
 * 包主密碼用的對稱金鑰（AC-11-2）。正式實作是 {@link KeystoreBioKeys}；單元測試用軟體金鑰的替身，
 * 好在 JVM 上驗證 {@link BioVault} 的流程。
 */
interface BioKeys {

    /**
     * 產生（或取代）別名的金鑰，回傳初始化成加密模式、還沒通過生物辨識的 {@code AES/GCM/NoPadding} 加密器。
     * 加密器要交給 {@code BiometricPrompt} 的 {@code CryptoObject}，通過驗證後才能 {@code doFinal}。
     *
     * @param alias 金鑰別名
     * @return 加密器（IV 由金鑰庫產生，從 {@link Cipher#getIV()} 取得）
     * @throws BioException 裝置沒有登錄強生物特徵、或金鑰庫錯誤
     */
    Cipher newEncryptCipher(String alias) throws BioException;

    /**
     * @param alias 金鑰別名
     * @param iv    加密時的 IV
     * @return 初始化成解密模式、還沒通過生物辨識的加密器
     * @throws BioException 金鑰不存在或已永久失效（{@link BioReason#KEY_INVALIDATED}）、或金鑰庫錯誤
     */
    Cipher decryptCipher(String alias, byte[] iv) throws BioException;

    /**
     * 刪除金鑰；不存在時不做事。
     *
     * @param alias 金鑰別名
     * @throws BioException 金鑰庫錯誤
     */
    void delete(String alias) throws BioException;

    /**
     * @param prefix 別名前綴
     * @return 以前綴開頭的所有別名
     * @throws BioException 金鑰庫錯誤
     */
    Set<String> aliases(String prefix) throws BioException;
}
