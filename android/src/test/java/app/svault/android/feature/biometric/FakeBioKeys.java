package app.svault.android.feature.biometric;

import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 以軟體 AES-GCM 金鑰代替 Keystore，可模擬「新增指紋後金鑰永久失效」。 */
final class FakeBioKeys implements BioKeys {

    final Map<String, SecretKey> keys = new HashMap<>();
    final Set<String> invalidated = new HashSet<>();
    int generated;
    /** 下一次 {@link #newEncryptCipher} 失敗（模擬金鑰庫錯誤）。 */
    boolean failNext;

    /** 模擬裝置新增生物特徵：所有既有金鑰永久失效。 */
    void enrollNewBiometric() {
        invalidated.addAll(keys.keySet());
    }

    @Override
    public Cipher newEncryptCipher(String alias) throws BioException {
        if (failNext) {
            failNext = false;
            throw new BioException(BioReason.FAILED);
        }
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            SecretKey key = generator.generateKey();
            keys.put(alias, key);
            invalidated.remove(alias);
            generated++;
            Cipher cipher = Cipher.getInstance(KeystoreBioKeys.TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new BioException(BioReason.FAILED, e);
        }
    }

    @Override
    public Cipher decryptCipher(String alias, byte[] iv) throws BioException {
        SecretKey key = keys.get(alias);
        if (key == null || invalidated.contains(alias)) {
            throw new BioException(BioReason.KEY_INVALIDATED);
        }
        try {
            Cipher cipher = Cipher.getInstance(KeystoreBioKeys.TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(KeystoreBioKeys.GCM_TAG_BITS, iv));
            return cipher;
        } catch (GeneralSecurityException e) {
            throw new BioException(BioReason.FAILED, e);
        }
    }

    @Override
    public void delete(String alias) {
        keys.remove(alias);
        invalidated.remove(alias);
    }

    @Override
    public Set<String> aliases(String prefix) {
        Set<String> result = new HashSet<>();
        for (String alias : keys.keySet()) {
            if (alias.startsWith(prefix)) {
                result.add(alias);
            }
        }
        return result;
    }
}
