package app.svault.android.feature.biometric;

import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyStore;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android Keystore 的 AES-256-GCM 金鑰（AC-11-2、AC-11-3）。
 *
 * <p>金鑰設定：每次使用都要通過驗證、驗證方式只限 Class 3（強）生物辨識（不接受裝置 PIN 代替，規格 §1）、
 * 新增生物特徵即永久失效。金鑰材料不離開金鑰庫（TEE），App 只拿得到操作代號。
 */
final class KeystoreBioKeys implements BioKeys {

    static final String TRANSFORMATION = "AES/GCM/NoPadding";
    static final int GCM_TAG_BITS = 128;
    private static final String PROVIDER = "AndroidKeyStore";

    @Override
    public Cipher newEncryptCipher(String alias) throws BioException {
        try {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
            generator.init(spec(alias));
            SecretKey key = generator.generateKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher;
        } catch (InvalidAlgorithmParameterException e) {
            // 沒有登錄任何強生物特徵時，要求生物辨識的金鑰產生不出來
            deleteQuietly(alias);
            throw new BioException(BioReason.NONE_ENROLLED, e);
        } catch (KeyPermanentlyInvalidatedException e) {
            deleteQuietly(alias);
            throw new BioException(BioReason.KEY_INVALIDATED, e);
        } catch (GeneralSecurityException | RuntimeException e) {
            deleteQuietly(alias);
            throw new BioException(BioReason.FAILED, e);
        }
    }

    @Override
    public Cipher decryptCipher(String alias, byte[] iv) throws BioException {
        try {
            Key key = keyStore().getKey(alias, null);
            if (!(key instanceof SecretKey)) {
                // 金鑰被系統移除（例如移除所有指紋、清除憑證）：和永久失效一樣處理
                throw new BioException(BioReason.KEY_INVALIDATED);
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher;
        } catch (KeyPermanentlyInvalidatedException e) {
            throw new BioException(BioReason.KEY_INVALIDATED, e);
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            throw new BioException(BioReason.FAILED, e);
        }
    }

    @Override
    public void delete(String alias) throws BioException {
        try {
            KeyStore ks = keyStore();
            if (ks.containsAlias(alias)) {
                ks.deleteEntry(alias);
            }
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            throw new BioException(BioReason.FAILED, e);
        }
    }

    @Override
    public Set<String> aliases(String prefix) throws BioException {
        try {
            Set<String> result = new HashSet<>();
            for (String alias : Collections.list(keyStore().aliases())) {
                if (alias.startsWith(prefix)) {
                    result.add(alias);
                }
            }
            return result;
        } catch (GeneralSecurityException | IOException | RuntimeException e) {
            throw new BioException(BioReason.FAILED, e);
        }
    }

    /**
     * @param alias 金鑰別名
     * @return 金鑰規格（儀器測試用 {@code KeyInfo} 確認實際生效的設定）
     */
    @SuppressWarnings("deprecation") // API 29 沒有 setUserAuthenticationParameters，只能用舊 API 表達「每次都要生物辨識」
    static KeyGenParameterSpec spec(String alias) {
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(alias,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                // AC-11-3：新增一枚指紋後金鑰永久失效，要求重新輸入主密碼
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 逾時 0＝每次使用都要驗證；只接受 Class 3，不接受裝置 PIN 代替（AC-11-2）
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            // API 29：-1＝每次使用都要驗證，且只接受生物辨識（Keystore 在 API 29 只認 Class 3）
            builder.setUserAuthenticationValidityDurationSeconds(-1);
        }
        return builder.build();
    }

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        return ks;
    }

    private void deleteQuietly(String alias) {
        try {
            delete(alias);
        } catch (BioException ignored) {
            // 已在處理另一個錯誤；殘留的金鑰在停用或下次啟用時會被取代
        }
    }
}
