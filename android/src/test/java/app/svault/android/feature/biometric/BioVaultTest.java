package app.svault.android.feature.biometric;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import org.junit.Before;
import org.junit.Test;

/** {@link BioVault} 的流程（軟體金鑰代替 Keystore；Keystore 本身的設定由儀器測試 {@code KeystoreBioKeysTest} 驗證）。 */
public class BioVaultTest {

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

    private static byte[] password() {
        return "probe1234".getBytes(StandardCharsets.UTF_8);
    }

    private void enable(String vaultId) throws BioException {
        Cipher c = bio.prepareEnable(vaultId);
        bio.completeEnable(vaultId, c, password());
    }

    @Test
    public void ac11_2_enableThenUnlockReturnsMasterPassword() throws Exception {
        enable(VAULT);
        assertTrue(bio.isEnabled(VAULT));
        assertTrue(keys.keys.containsKey("svault_bio_" + VAULT));

        byte[] pw = bio.completeUnlock(VAULT, bio.prepareUnlock(VAULT));
        assertArrayEquals(password(), pw);
    }

    @Test
    public void ac11_2_storedDataIsNotPlaintext() throws Exception {
        enable(VAULT);
        BioSecretStore.Sealed sealed = store.data.get(VAULT);
        assertNotNull(sealed);
        String stored = new String(sealed.ciphertext(), StandardCharsets.ISO_8859_1);
        assertFalse("密文不得含主密碼明文", stored.contains("probe1234"));
        assertEquals("GCM IV 長度", 12, sealed.iv().length);
    }

    @Test
    public void ac11_3_newBiometricInvalidatesAndCleansUp() throws Exception {
        enable(VAULT);
        keys.enrollNewBiometric();

        BioException e = assertThrows(BioException.class, () -> bio.prepareUnlock(VAULT));
        assertEquals(BioReason.KEY_INVALIDATED, e.reason());
        assertFalse("密文已刪除", bio.isEnabled(VAULT));
        assertFalse("金鑰已刪除", keys.keys.containsKey("svault_bio_" + VAULT));
        // 之後再解鎖：視為沒啟用，要求重新啟用
        assertEquals(BioReason.NOT_ENABLED,
                assertThrows(BioException.class, () -> bio.prepareUnlock(VAULT)).reason());
    }

    @Test
    public void ac11_3_reenableAfterInvalidationWorks() throws Exception {
        enable(VAULT);
        keys.enrollNewBiometric();
        assertThrows(BioException.class, () -> bio.prepareUnlock(VAULT));

        enable(VAULT);
        assertArrayEquals(password(), bio.completeUnlock(VAULT, bio.prepareUnlock(VAULT)));
    }

    @Test
    public void ac11_4_notEnabledReportsReason() {
        BioException e = assertThrows(BioException.class, () -> bio.prepareUnlock(VAULT));
        assertEquals(BioReason.NOT_ENABLED, e.reason());
    }

    @Test
    public void ac11_4_corruptDataIsRemoved() throws Exception {
        enable(VAULT);
        store.corrupt.add(VAULT);

        BioException e = assertThrows(BioException.class, () -> bio.prepareUnlock(VAULT));
        assertEquals(BioReason.CORRUPT_DATA, e.reason());
        assertFalse(keys.keys.containsKey("svault_bio_" + VAULT));
        assertFalse(bio.isEnabled(VAULT));
    }

    @Test
    public void ac11_2_ciphertextBoundToVault() throws Exception {
        enable(VAULT);
        enable(OTHER);
        // 把 VAULT 的密文搬到 OTHER：附加資料（保管庫 ID）不同，解不開，而且資料被清掉
        BioSecretStore.Sealed moved = store.data.get(VAULT);
        store.put(OTHER, moved);
        Cipher forged = keys.decryptCipher("svault_bio_" + VAULT, moved.iv());

        BioException e = assertThrows(BioException.class, () -> bio.completeUnlock(OTHER, forged));
        assertEquals(BioReason.CORRUPT_DATA, e.reason());
        assertFalse(bio.isEnabled(OTHER));
        assertTrue("別的保管庫不受影響", bio.isEnabled(VAULT));
    }

    @Test
    public void reenableReplacesKeyAndOldCiphertext() throws Exception {
        enable(VAULT);
        bio.prepareEnable(VAULT);
        assertFalse("產生新金鑰時先移除舊密文", bio.isEnabled(VAULT));
        assertEquals(2, keys.generated);
    }

    @Test
    public void disableRemovesKeyAndCiphertext() throws Exception {
        enable(VAULT);
        enable(OTHER);
        bio.disable(VAULT);
        assertFalse(bio.isEnabled(VAULT));
        assertFalse(keys.keys.containsKey("svault_bio_" + VAULT));
        assertTrue(bio.isEnabled(OTHER));
    }

    @Test
    public void settingsCountsEnabledVaultsAndLeftoverKeys() throws Exception {
        // 設定段落（T-71）：「已在 n 個保管庫啟用」與「停用」按鈕要不要出現
        assertEquals(0, bio.enabledCount());
        assertFalse(bio.hasAnyData());
        enable(VAULT);
        enable(OTHER);
        assertEquals(2, bio.enabledCount());
        assertTrue(bio.hasAnyData());
        bio.disableAll();
        assertEquals(0, bio.enabledCount());
        assertFalse(bio.hasAnyData());
        // 啟用做到一半留下的金鑰也算：停用時一起清掉
        keys.newEncryptCipher(BioVault.alias(VAULT));
        assertEquals(0, bio.enabledCount());
        assertTrue(bio.hasAnyData());
    }

    @Test
    public void disableAllRemovesEverything() throws Exception {
        enable(VAULT);
        enable(OTHER);
        keys.keys.put("unrelated_alias", keys.keys.get("svault_bio_" + VAULT));
        bio.disableAll();
        assertTrue(store.vaultIds().isEmpty());
        assertEquals(java.util.Set.of("unrelated_alias"), keys.keys.keySet());
    }

    @Test
    public void vaultIdIsNormalizedAndValidated() throws Exception {
        enable(VAULT.toUpperCase(java.util.Locale.ROOT));
        assertTrue(bio.isEnabled(VAULT));
        assertThrows(IllegalArgumentException.class, () -> bio.prepareEnable("../../evil"));
    }

    @Test
    public void completeEnableFailureLeavesNoHalfState() throws Exception {
        Cipher c = bio.prepareEnable(VAULT);
        c.updateAAD(new byte[] {1});
        c.doFinal(new byte[] {1}); // 用掉加密器：同一個 IV 不能再加密，completeEnable 會失敗
        BioException e = assertThrows(BioException.class, () -> bio.completeEnable(VAULT, c, password()));
        assertEquals(BioReason.FAILED, e.reason());
        assertFalse(bio.isEnabled(VAULT));
        assertTrue(keys.keys.isEmpty());
    }
}
