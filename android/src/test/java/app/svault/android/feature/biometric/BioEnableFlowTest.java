package app.svault.android.feature.biometric;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.svault.core.module.MasterPasswordCheck;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import org.junit.Before;
import org.junit.Test;

/**
 * 設定頁「啟用生物辨識解鎖」的流程（{@link BioEnableFlow}，T-72；AC-11-1 的啟用、AC-11-2、AC-11-4）：確認主密碼 →
 * 產生金鑰 → 提示框 → 加密存起來。每一條路徑主密碼位元組都要清零；沒有成功時不留下沒有密文的金鑰（否則解鎖畫面會出現
 * 按鈕）。外殼的確認與提示框都換成直接回呼的替身；真的 {@code BiometricPrompt} 由儀器與人手驗證。
 */
public class BioEnableFlowTest {

    private static final String VAULT = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    private FakeBioKeys keys;
    private MemoryBioSecretStore store;
    private BioVault bio;
    private final Recorder ui = new Recorder();
    private byte[] handed;
    private int prompts;

    /** 記下畫面收到的結果。 */
    private static final class Recorder implements BioEnableFlow.Listener {
        final List<String> events = new ArrayList<>();
        MasterPasswordCheck.Status rejected;
        BioReason failure;
        String enabled;

        @Override
        public void onVerified() {
            events.add("verified");
        }

        @Override
        public void onRejected(MasterPasswordCheck.Status status) {
            events.add("rejected");
            rejected = status;
        }

        @Override
        public void onEnabled(String vaultId) {
            events.add("enabled");
            enabled = vaultId;
        }

        @Override
        public void onCanceled() {
            events.add("canceled");
        }

        @Override
        public void onFailed(BioReason reason) {
            events.add("failed");
            failure = reason;
        }
    }

    @Before
    public void setUp() {
        keys = new FakeBioKeys();
        store = new MemoryBioSecretStore();
        bio = new BioVault(keys, store);
    }

    private byte[] password() {
        handed = "probe1234".getBytes(StandardCharsets.UTF_8);
        return handed;
    }

    private void assertWiped() {
        assertArrayEquals("主密碼位元組已清零", new byte[handed.length], handed);
    }

    /** 外殼確認的替身：一律回傳 {@code check}，並確認交來的是使用者輸入的主密碼。 */
    private BioEnableFlow.Verifier answers(MasterPasswordCheck check) {
        return (pw, cb) -> {
            assertArrayEquals("probe1234".getBytes(StandardCharsets.UTF_8), pw);
            cb.accept(check);
        };
    }

    private BioEnableFlow flow(BioReason unavailable, BioEnableFlow.Verifier verifier, BioUnlockFlow.Prompter p) {
        return new BioEnableFlow(bio, unavailable, verifier, p, ui);
    }

    /** 提示框替身：通過驗證，原樣交回加密器（軟體金鑰不需要真的驗證）。 */
    private BioUnlockFlow.Prompter passes() {
        return (cipher, cb) -> {
            prompts++;
            cb.onAuthenticated(cipher);
        };
    }

    private BioUnlockFlow.Prompter errors(BioReason reason) {
        return (cipher, cb) -> {
            prompts++;
            cb.onError(new BioException(reason));
        };
    }

    private byte[] unlock(String vaultId) throws BioException {
        Cipher c = bio.prepareUnlock(vaultId);
        return bio.completeUnlock(vaultId, c);
    }

    @Test
    public void ac11_1_enablesWithConfirmedPassword() throws Exception {
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), passes()).start(password());
        assertEquals(List.of("verified", "enabled"), ui.events);
        assertEquals(VAULT, ui.enabled);
        assertTrue(bio.isEnabled(VAULT));
        assertTrue("解鎖畫面會出現按鈕", BioVault.hasKey(keys, VAULT));
        assertArrayEquals("之後用生物辨識取回的是同一組主密碼（AC-11-2）",
                "probe1234".getBytes(StandardCharsets.UTF_8), unlock(VAULT));
        assertWiped();
    }

    @Test
    public void ac11_1_usesConfirmedVaultId() throws Exception {
        // 確認結果帶的 ID 才是要啟用的保管庫（D-165 第 5 點）；流程本身不另外取 ID
        String confirmed = "6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        flow(null, answers(MasterPasswordCheck.matched(confirmed)), passes()).start(password());
        assertTrue(bio.isEnabled(confirmed));
        assertFalse(bio.isEnabled(VAULT));
    }

    @Test
    public void wrongPasswordStopsBeforePrompt() {
        flow(null, answers(MasterPasswordCheck.rejected(MasterPasswordCheck.Status.MISMATCH)), passes())
                .start(password());
        assertEquals(List.of("rejected"), ui.events);
        assertEquals(MasterPasswordCheck.Status.MISMATCH, ui.rejected);
        assertEquals("沒有出現提示框", 0, prompts);
        assertFalse(BioVault.hasKey(keys, VAULT));
        assertWiped();
    }

    @Test
    public void otherRejectionsArePassedOn() {
        for (MasterPasswordCheck.Status s : new MasterPasswordCheck.Status[] {
                MasterPasswordCheck.Status.TOO_MANY_ATTEMPTS, MasterPasswordCheck.Status.LOCKED,
                MasterPasswordCheck.Status.FAILED}) {
            ui.events.clear();
            flow(null, answers(MasterPasswordCheck.rejected(s)), passes()).start(password());
            assertEquals(s, ui.rejected);
            assertEquals(List.of("rejected"), ui.events);
            assertWiped();
        }
        assertEquals(0, prompts);
    }

    @Test
    public void ac11_4_unavailableDeviceDoesNotAskHost() {
        flow(BioReason.NO_STRONG_BIOMETRIC, (pw, cb) -> {
            throw new AssertionError("裝置不支援時不用確認主密碼");
        }, passes()).start(password());
        assertEquals(List.of("failed"), ui.events);
        assertEquals(BioReason.NO_STRONG_BIOMETRIC, ui.failure);
        assertWiped();
    }

    @Test
    public void canceledPromptLeavesNoKey() {
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), errors(BioReason.CANCELED)).start(password());
        assertEquals(List.of("verified", "canceled"), ui.events);
        assertFalse("沒有密文的金鑰已刪除", BioVault.hasKey(keys, VAULT));
        assertFalse(bio.isEnabled(VAULT));
        assertWiped();
    }

    @Test
    public void ac11_4_failedPromptLeavesNoKey() {
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), errors(BioReason.LOCKOUT)).start(password());
        assertEquals(List.of("verified", "failed"), ui.events);
        assertEquals(BioReason.LOCKOUT, ui.failure);
        assertFalse(BioVault.hasKey(keys, VAULT));
        assertWiped();
    }

    @Test
    public void reEnableReplacesOldData() throws Exception {
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), passes()).start(password());
        // 再啟用一次但取消：舊的密文已被 prepareEnable 移除，金鑰也清掉，不會留下解不開的資料
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), errors(BioReason.CANCELED)).start(password());
        assertFalse(bio.isEnabled(VAULT));
        assertFalse(BioVault.hasKey(keys, VAULT));
    }

    @Test
    public void keystoreErrorBeforePromptFails() {
        keys.failNext = true;
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), passes()).start(password());
        assertEquals(List.of("verified", "failed"), ui.events);
        assertEquals(0, prompts);
        assertWiped();
    }

    @Test
    public void unexpectedErrorsAreContained() {
        // 外殼丟例外（例如代號不對的 SecurityException）、提示框替身丟例外：都要清零並回報失敗，不往外丟
        flow(null, (pw, cb) -> {
            throw new SecurityException("測試");
        }, passes()).start(password());
        assertEquals(BioReason.FAILED, ui.failure);
        assertWiped();

        ui.events.clear();
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), (cipher, cb) -> {
            throw new IllegalStateException("測試");
        }).start(password());
        assertEquals(List.of("verified", "failed"), ui.events);
        assertFalse(BioVault.hasKey(keys, VAULT));
        assertWiped();
    }

    @Test
    public void storeErrorWhileSavingFails() {
        store.failPut = true;
        flow(null, answers(MasterPasswordCheck.matched(VAULT)), passes()).start(password());
        assertEquals(List.of("verified", "failed"), ui.events);
        assertNull(ui.enabled);
        assertFalse("completeEnable 失敗時刪除金鑰", BioVault.hasKey(keys, VAULT));
        assertWiped();
    }
}
