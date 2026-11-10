package app.svault.android.feature.biometric;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 實機的 Keystore 金鑰設定與私有偏好（AC-11-2、AC-11-3）。需要裝置已登錄指紋（SETUP S-11）：
 * 要求生物辨識的金鑰在沒有登錄生物特徵時產生不出來。
 */
@RunWith(AndroidJUnit4.class)
public class KeystoreBioKeysTest {

    private static final String VAULT = "00000000-0000-4000-8000-0000000b1070";
    private static final String ALIAS = BioVault.alias(VAULT);

    private Context context;
    private final KeystoreBioKeys keys = new KeystoreBioKeys();

    @Before
    public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        // 不用 Assume：AGP 會算成失敗，而且這台測試機本來就該有指紋（SETUP S-11），沒有時要明確看到原因
        assertNull("需要已登錄指紋的裝置（SETUP S-11）", BioPrompt.strongUnavailableReason(context));
        BioVault.of(context).disable(VAULT);
    }

    @After
    public void tearDown() throws Exception {
        BioVault.of(context).disable(VAULT);
    }

    private KeyInfo keyInfo() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey key = (SecretKey) ks.getKey(ALIAS, null);
        SecretKeyFactory factory = SecretKeyFactory.getInstance(key.getAlgorithm(), "AndroidKeyStore");
        return (KeyInfo) factory.getKeySpec(key, KeyInfo.class);
    }

    @Test
    @SuppressWarnings("deprecation") // isInsideSecureHardware：API 31 前沒有 getSecurityLevel
    public void ac11_2_keyRequiresStrongBiometricEveryUse() throws Exception {
        keys.newEncryptCipher(ALIAS);
        KeyInfo info = keyInfo();

        assertEquals(256, info.getKeySize());
        assertTrue("必須通過使用者驗證", info.isUserAuthenticationRequired());
        assertEquals("每次使用都要驗證（逾時 0）", 0, info.getUserAuthenticationValidityDurationSeconds());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            assertEquals("只接受 Class 3 生物辨識，不接受裝置 PIN",
                    KeyProperties.AUTH_BIOMETRIC_STRONG, info.getUserAuthenticationType());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int level = info.getSecurityLevel();
            assertTrue("金鑰在安全硬體（TEE／StrongBox）內：" + level,
                    level == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT
                            || level == KeyProperties.SECURITY_LEVEL_STRONGBOX);
        } else {
            assertTrue(info.isInsideSecureHardware());
        }
    }

    @Test
    public void ac11_3_keyInvalidatedByNewEnrollment() throws Exception {
        keys.newEncryptCipher(ALIAS);
        assertTrue("新增生物特徵即失效", keyInfo().isInvalidatedByBiometricEnrollment());
    }

    @Test
    public void ac11_2_keyUnusableWithoutBiometric() throws Exception {
        Cipher cipher = keys.newEncryptCipher(ALIAS);
        byte[] secret = "probe1234".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // 沒有經過 BiometricPrompt：金鑰庫拒絕操作，拿不到密文
        assertThrows(Exception.class, () -> {
            cipher.updateAAD(VAULT.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cipher.doFinal(secret);
        });
    }

    @Test
    public void ac11_2_keyMaterialNotExtractable() throws Exception {
        keys.newEncryptCipher(ALIAS);
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        assertNull("Keystore 金鑰不能匯出原始位元組", ((SecretKey) ks.getKey(ALIAS, null)).getEncoded());
    }

    @Test
    public void ac11_3_missingKeyReportsInvalidated() throws Exception {
        keys.newEncryptCipher(ALIAS);
        keys.delete(ALIAS);
        BioException e = assertThrows(BioException.class, () -> keys.decryptCipher(ALIAS, new byte[12]));
        assertEquals(BioReason.KEY_INVALIDATED, e.reason());
    }

    @Test
    public void aliasesAndDelete() throws Exception {
        keys.newEncryptCipher(ALIAS);
        assertTrue(keys.aliases(BioVault.ALIAS_PREFIX).contains(ALIAS));
        keys.delete(ALIAS);
        assertFalse(keys.aliases(BioVault.ALIAS_PREFIX).contains(ALIAS));
        keys.delete(ALIAS); // 不存在時不做事
    }

    @Test
    public void prefsStoreRoundTripAndCorruption() throws Exception {
        PrefsBioSecretStore store = new PrefsBioSecretStore(context);
        byte[] iv = new byte[12];
        byte[] ct = {1, 2, 3, 4};
        store.put(VAULT, new BioSecretStore.Sealed(iv, ct));
        assertArrayEquals(ct, store.get(VAULT).ciphertext());
        assertTrue(store.vaultIds().contains(VAULT));

        context.getSharedPreferences(PrefsBioSecretStore.FILE, Context.MODE_PRIVATE).edit()
                .putString(PrefsBioSecretStore.KEY_PREFIX + VAULT, "v1:not-base64!").commit();
        assertEquals(BioReason.CORRUPT_DATA, assertThrows(BioException.class, () -> store.get(VAULT)).reason());

        store.remove(VAULT);
        assertNull(store.get(VAULT));
    }
}
