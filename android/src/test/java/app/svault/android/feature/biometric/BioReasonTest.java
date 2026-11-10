package app.svault.android.feature.biometric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** AC-11-4：無法使用或失敗時，退回主密碼並顯示對應原因。 */
public class BioReasonTest {

    @Test
    public void ac11_4_onlyWeakOrNoBiometricFallsBackWithReason() {
        assertNull(BioReason.fromCanAuthenticate(BiometricManager.BIOMETRIC_SUCCESS));
        // 只有 Class 2 的裝置以 BIOMETRIC_STRONG 查詢時回 NO_HARDWARE（或 UNSUPPORTED）
        assertEquals(BioReason.NO_STRONG_BIOMETRIC,
                BioReason.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE));
        assertEquals(BioReason.NO_STRONG_BIOMETRIC,
                BioReason.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED));
        assertEquals(BioReason.NONE_ENROLLED,
                BioReason.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED));
        assertEquals(BioReason.HARDWARE_UNAVAILABLE,
                BioReason.fromCanAuthenticate(BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE));
    }

    @Test
    public void ac11_4_repeatedFailuresMapToLockout() {
        assertEquals(BioReason.LOCKOUT, BioReason.fromPromptError(BiometricPrompt.ERROR_LOCKOUT));
        assertEquals(BioReason.LOCKOUT_PERMANENT, BioReason.fromPromptError(BiometricPrompt.ERROR_LOCKOUT_PERMANENT));
    }

    @Test
    public void ac11_4_cancelMapsToCanceled() {
        assertEquals(BioReason.CANCELED, BioReason.fromPromptError(BiometricPrompt.ERROR_NEGATIVE_BUTTON));
        assertEquals(BioReason.CANCELED, BioReason.fromPromptError(BiometricPrompt.ERROR_USER_CANCELED));
        assertEquals(BioReason.FAILED, BioReason.fromPromptError(BiometricPrompt.ERROR_VENDOR));
    }

    @Test
    public void ac11_4_systemCancelIsNotUserCancel() {
        // T-71 實機：Pixel 9 Pro（指紋＋臉部都是 Class 3）連續失敗時，指紋回 LOCKOUT_PERMANENT、臉部回 ERROR_CANCELED，
        // App 收到的是 ERROR_CANCELED；當成使用者取消就不會顯示原因
        assertEquals(BioReason.INTERRUPTED, BioReason.fromPromptError(BiometricPrompt.ERROR_CANCELED));
        assertEquals(BioReason.INTERRUPTED, BioReason.fromPromptError(BiometricPrompt.ERROR_CANCELED, 0));
    }

    @Test
    public void ac11_4_userCancelAfterFailuresShowsReason() {
        // T-71 實機：Pixel 9 Pro（Android 17）在提示框裡鎖定時 App 收到 ERROR_USER_CANCELED，和按返回鍵同一個碼；
        // 這次按錯過就不當成使用者取消，要顯示原因
        assertEquals(BioReason.INTERRUPTED, BioReason.fromPromptError(BiometricPrompt.ERROR_USER_CANCELED, 5));
        assertEquals(BioReason.CANCELED, BioReason.fromPromptError(BiometricPrompt.ERROR_USER_CANCELED, 0));
        // 按「使用主密碼」一定是使用者的選擇
        assertEquals(BioReason.CANCELED, BioReason.fromPromptError(BiometricPrompt.ERROR_NEGATIVE_BUTTON, 3));
        assertEquals(BioReason.LOCKOUT, BioReason.fromPromptError(BiometricPrompt.ERROR_LOCKOUT, 5));
    }

    @Test
    public void ac11_4_everyReasonHasItsOwnMessage() {
        Set<Integer> seen = new HashSet<>();
        for (BioReason r : BioReason.values()) {
            seen.add(r.messageRes());
        }
        assertEquals(BioReason.values().length, seen.size());
    }
}
