package app.svault.android.feature.biometric;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.svault.android.shell.ext.UnlockProvider;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import org.junit.Before;
import org.junit.Test;

/**
 * 解鎖畫面上的生物辨識流程（{@link BioUnlockFlow}，T-71）：成功時把主密碼交給外殼，任何失敗都退回主密碼輸入並顯示原因
 * （AC-11-3、AC-11-4）。提示框換成直接回呼的替身；真的 {@code BiometricPrompt} 由儀器與人手驗證。
 */
public class BioUnlockFlowTest {

    private static final String VAULT = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String OTHER = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";

    private FakeBioKeys keys;
    private MemoryBioSecretStore store;
    private BioVault bio;
    private final List<BioReason> notices = new ArrayList<>();
    private final Recorder shell = new Recorder();
    private int prompts;

    /** 記下外殼收到的結果。 */
    private static final class Recorder implements UnlockProvider.Callback {
        byte[] password;
        int cancels;
        Throwable failure;

        @Override
        public void onSuccess(byte[] masterPassword) {
            password = masterPassword.clone();
        }

        @Override
        public void onCancel() {
            cancels++;
        }

        @Override
        public void onFailure(Throwable error) {
            failure = error;
        }
    }

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

    /** 提示框替身：通過驗證，原樣交回加密器（軟體金鑰不需要真的驗證）。 */
    private BioUnlockFlow flow(BioReason unavailable) {
        return new BioUnlockFlow(bio, unavailable, (cipher, cb) -> {
            prompts++;
            cb.onAuthenticated(cipher);
        }, notices::add);
    }

    /** 提示框替身：回報錯誤（取消、鎖定等）。 */
    private BioUnlockFlow failingPrompt(BioReason error) {
        return new BioUnlockFlow(bio, null, (cipher, cb) -> {
            prompts++;
            cb.onError(new BioException(error));
        }, notices::add);
    }

    private BioReason failureReason() {
        assertTrue("應退回主密碼輸入：" + shell.failure, shell.failure instanceof BioException);
        return ((BioException) shell.failure).reason();
    }

    @Test
    public void ac11_1_buttonOnlyForVaultWithKey() throws Exception {
        assertFalse(BioVault.hasKey(keys, VAULT));
        enable(VAULT);
        assertTrue(BioVault.hasKey(keys, VAULT));
        assertTrue("大小寫不同仍是同一個保管庫", BioVault.hasKey(keys, VAULT.toUpperCase(java.util.Locale.ROOT)));
        assertFalse(BioVault.hasKey(keys, OTHER));
        assertFalse("格式不對的 ID 不顯示按鈕", BioVault.hasKey(keys, "not-a-uuid"));
        assertFalse(BioVault.hasKey(keys, null));
    }

    @Test
    public void ac11_2_unlockHandsMasterPasswordToShell() throws Exception {
        enable(VAULT);
        flow(null).start(VAULT, shell);
        assertArrayEquals(password(), shell.password);
        assertNull(shell.failure);
        assertTrue(notices.isEmpty());
        assertEquals(1, prompts);
    }

    @Test
    public void ac11_3_newBiometricFallsBackWithReason() throws Exception {
        enable(VAULT);
        keys.enrollNewBiometric();
        flow(null).start(VAULT, shell);
        assertEquals(BioReason.KEY_INVALIDATED, failureReason());
        assertEquals(List.of(BioReason.KEY_INVALIDATED), notices);
        assertEquals("金鑰失效時不顯示提示框", 0, prompts);
        assertFalse("金鑰與密文已刪除，下次不再顯示按鈕", BioVault.hasKey(keys, VAULT));
        assertFalse(bio.isEnabled(VAULT));
        assertNull(shell.password);
    }

    @Test
    public void ac11_4_noStrongBiometricFallsBackWithReason() throws Exception {
        enable(VAULT);
        flow(BioReason.NO_STRONG_BIOMETRIC).start(VAULT, shell);
        assertEquals(BioReason.NO_STRONG_BIOMETRIC, failureReason());
        assertEquals(List.of(BioReason.NO_STRONG_BIOMETRIC), notices);
        assertEquals(0, prompts);
    }

    @Test
    public void ac11_4_lockoutFallsBackWithReason() throws Exception {
        enable(VAULT);
        failingPrompt(BioReason.LOCKOUT).start(VAULT, shell);
        assertEquals(BioReason.LOCKOUT, failureReason());
        assertEquals(List.of(BioReason.LOCKOUT), notices);
        assertTrue("鎖定不是金鑰問題，保留設定", bio.isEnabled(VAULT));
    }

    @Test
    public void ac11_4_systemInterruptFallsBackWithReason() throws Exception {
        // 多個生物辨識其中一個鎖定時系統回 ERROR_CANCELED（實機），不是使用者取消
        enable(VAULT);
        failingPrompt(BioReason.INTERRUPTED).start(VAULT, shell);
        assertEquals(BioReason.INTERRUPTED, failureReason());
        assertEquals(List.of(BioReason.INTERRUPTED), notices);
        assertEquals(0, shell.cancels);
    }

    @Test
    public void ac11_4_cancelStaysOnPasswordWithoutMessage() throws Exception {
        enable(VAULT);
        failingPrompt(BioReason.CANCELED).start(VAULT, shell);
        assertEquals(1, shell.cancels);
        assertNull(shell.failure);
        assertTrue("使用者自己按「使用主密碼」，不另外顯示訊息", notices.isEmpty());
    }

    @Test
    public void ac11_4_unexpectedErrorFallsBackWithReason() throws Exception {
        enable(VAULT);
        new BioUnlockFlow(bio, null, (cipher, cb) -> {
            throw new IllegalStateException("測試：提示框建立失敗");
        }, notices::add).start(VAULT, shell);
        assertEquals(BioReason.FAILED, failureReason());
        assertEquals(List.of(BioReason.FAILED), notices);
    }

    @Test
    public void ac11_4_corruptDataFallsBackAndCleansUp() throws Exception {
        enable(VAULT);
        store.corrupt.add(VAULT);
        flow(null).start(VAULT, shell);
        assertEquals(BioReason.CORRUPT_DATA, failureReason());
        assertFalse(BioVault.hasKey(keys, VAULT));
    }

    @Test
    public void ac11_4_keyWithoutCiphertextIsCleanedUp() throws Exception {
        // 啟用做到一半（金鑰已產生、沒有密文）留下的金鑰：按鈕會出現，按下後清掉並說明
        keys.newEncryptCipher(BioVault.alias(VAULT));
        assertTrue(BioVault.hasKey(keys, VAULT));
        flow(null).start(VAULT, shell);
        assertEquals(BioReason.NOT_ENABLED, failureReason());
        assertFalse(BioVault.hasKey(keys, VAULT));
    }
}
