package app.svault.android.feature.biometric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import javax.crypto.Cipher;
import org.junit.Before;
import org.junit.Test;

/**
 * 設定段落依目前保管庫顯示啟用／停用（{@link BioSettingsState}，T-72；AC-11-1、AC-11-4）。整台裝置的「停用所有保管庫」
 * 只在別的保管庫也有資料時出現；模組停用時刪除全部（{@link BiometricAndroidFeature#onDisabled}，規格 §1）。
 */
public class BioSettingsStateTest {

    private static final String VAULT = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String OTHER = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

    private FakeBioKeys keys;
    private MemoryBioSecretStore store;
    private BioVault bio;

    @Before
    public void setUp() {
        keys = new FakeBioKeys();
        store = new MemoryBioSecretStore();
        bio = new BioVault(keys, store);
    }

    private void enable(String vaultId) throws BioException {
        Cipher c = bio.prepareEnable(vaultId);
        bio.completeEnable(vaultId, c, "probe1234".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void ac11_1_notEnabledOffersEnable() throws Exception {
        BioSettingsState s = BioSettingsState.read(bio, Optional.of(VAULT), null);
        assertEquals(VAULT, s.vaultId());
        assertFalse(s.currentEnabled());
        assertTrue(s.canEnable());
        assertFalse(s.canDisableCurrent());
        assertFalse(s.canDisableAll());
        assertEquals(0, s.othersEnabled());
    }

    @Test
    public void ac11_1_enabledOffersDisable() throws Exception {
        enable(VAULT);
        BioSettingsState s = BioSettingsState.read(bio, Optional.of(VAULT), null);
        assertTrue(s.currentEnabled());
        assertFalse(s.canEnable());
        assertTrue(s.canDisableCurrent());
        assertFalse("只有目前的保管庫有資料時不另外提供「停用所有」", s.canDisableAll());
    }

    @Test
    public void vaultIdIsNormalized() throws Exception {
        enable(VAULT);
        assertTrue(BioSettingsState.read(bio, Optional.of(VAULT.toUpperCase(java.util.Locale.ROOT)), null)
                .currentEnabled());
    }

    @Test
    public void otherVaultsAreCountedAndCanBeCleared() throws Exception {
        enable(OTHER);
        BioSettingsState s = BioSettingsState.read(bio, Optional.of(VAULT), null);
        assertFalse(s.currentEnabled());
        assertEquals(1, s.othersEnabled());
        assertTrue(s.canDisableAll());

        // 別的保管庫只留下金鑰（啟用做到一半）也算有資料
        bio.disable(OTHER);
        keys.newEncryptCipher(BioVault.alias(OTHER));
        s = BioSettingsState.read(bio, Optional.of(VAULT), null);
        assertEquals(0, s.othersEnabled());
        assertTrue(s.canDisableAll());
    }

    @Test
    public void ac11_4_unsupportedDeviceCannotEnable() throws Exception {
        BioSettingsState s = BioSettingsState.read(bio, Optional.of(VAULT), BioReason.NONE_ENROLLED);
        assertFalse(s.canEnable());
        assertEquals(BioReason.NONE_ENROLLED, s.unavailable());
    }

    @Test
    public void ac11_4_enabledButUnsupportedCanStillDisable() throws Exception {
        enable(VAULT);
        BioSettingsState s = BioSettingsState.read(bio, Optional.of(VAULT), BioReason.NO_STRONG_BIOMETRIC);
        assertTrue(s.canDisableCurrent());
    }

    @Test
    public void lockedShowsNoVaultActions() throws Exception {
        enable(OTHER);
        BioSettingsState s = BioSettingsState.read(bio, Optional.empty(), null);
        assertNull(s.vaultId());
        assertFalse(s.canEnable());
        assertFalse(s.canDisableCurrent());
        assertTrue("上鎖時仍可停用全部", s.canDisableAll());
        assertEquals(1, s.othersEnabled());
    }

    @Test
    public void moduleDisabledClearsEverything() throws Exception {
        enable(VAULT);
        enable(OTHER);
        keys.newEncryptCipher(BioVault.alias("00000000-0000-4000-8000-000000000001"));
        BiometricAndroidFeature.clearAll(bio);
        assertFalse(bio.hasAnyData());
        // 每次啟動都會呼叫：已經清過時不做事
        BiometricAndroidFeature.clearAll(bio);
        assertFalse(bio.hasAnyData());
    }
}
