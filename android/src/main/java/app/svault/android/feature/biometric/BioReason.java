package app.svault.android.feature.biometric;

import androidx.annotation.StringRes;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import app.svault.android.R;

/**
 * 生物辨識無法使用或失敗的原因（AC-11-3、AC-11-4）。每個原因對應一句給使用者看的說明，外殼退回主密碼輸入時顯示。
 */
public enum BioReason {

    /** 裝置沒有 Class 3（強）的生物辨識硬體，或只有 Class 2 以下（例如部分手機的臉部辨識）（AC-11-4）。 */
    NO_STRONG_BIOMETRIC(R.string.bio_reason_no_strong),
    /** 有硬體但沒有登錄任何強生物特徵。 */
    NONE_ENROLLED(R.string.bio_reason_none_enrolled),
    /** 硬體暫時無法使用、或需要系統安全性更新。 */
    HARDWARE_UNAVAILABLE(R.string.bio_reason_hw_unavailable),
    /** 這個保管庫還沒啟用生物辨識解鎖。 */
    NOT_ENABLED(R.string.bio_reason_not_enabled),
    /** 裝置新增或移除了生物特徵，Keystore 金鑰已永久失效（AC-11-3）；金鑰與密文已刪除。 */
    KEY_INVALIDATED(R.string.bio_reason_key_invalidated),
    /** 連續失敗太多次，暫時鎖定（AC-11-4）。 */
    LOCKOUT(R.string.bio_reason_lockout),
    /** 連續失敗太多次，要先用螢幕鎖解除才能再用生物辨識（AC-11-4）。 */
    LOCKOUT_PERMANENT(R.string.bio_reason_lockout_permanent),
    /** 使用者自己取消（按「使用主密碼」、返回鍵或點提示框外面）；解鎖畫面不另外顯示訊息。 */
    CANCELED(R.string.bio_reason_canceled),
    /**
     * 提示框沒有成功就結束、又不是使用者單純取消：系統中斷（{@code ERROR_CANCELED}，感應器改作他用、切到別的畫面），
     * 或按錯過之後才「取消」（有些裝置在提示框裡鎖定時回 {@code ERROR_USER_CANCELED}，T-71 實機）。要顯示原因（AC-11-4）。
     */
    INTERRUPTED(R.string.bio_reason_interrupted),
    /** 儲存的密文讀不出來或驗證失敗；已刪除，要重新啟用。 */
    CORRUPT_DATA(R.string.bio_reason_corrupt),
    /** Keystore 或其他未分類的錯誤。 */
    FAILED(R.string.bio_reason_failed);

    @StringRes
    private final int messageRes;

    BioReason(@StringRes int messageRes) {
        this.messageRes = messageRes;
    }

    /** @return 給使用者看的原因（繁體中文字串資源） */
    @StringRes
    public int messageRes() {
        return messageRes;
    }

    /**
     * 把 {@link BiometricManager#canAuthenticate(int)}（以 {@code BIOMETRIC_STRONG} 查詢）的結果轉成原因。
     *
     * @param status {@code canAuthenticate} 的回傳值
     * @return 原因；{@code BIOMETRIC_SUCCESS} 時為 {@code null}（可以使用）
     */
    public static BioReason fromCanAuthenticate(int status) {
        switch (status) {
            case BiometricManager.BIOMETRIC_SUCCESS:
                return null;
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED:
                return NONE_ENROLLED;
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE:
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED:
                return HARDWARE_UNAVAILABLE;
            default:
                // NO_HARDWARE、UNSUPPORTED、STATUS_UNKNOWN：以 STRONG 查詢時只有弱生物辨識的裝置也落在這裡
                return NO_STRONG_BIOMETRIC;
        }
    }

    /**
     * 把 {@link BiometricPrompt.AuthenticationCallback#onAuthenticationError} 的錯誤碼轉成原因。
     * 單次比對失敗（{@code onAuthenticationFailed}）不會走到這裡：提示框會留著讓使用者再試，
     * 連續失敗到上限時系統才回 {@code ERROR_LOCKOUT}。
     *
     * @param errorCode 錯誤碼
     * @return 原因
     */
    public static BioReason fromPromptError(int errorCode) {
        return fromPromptError(errorCode, 0);
    }

    /**
     * 同 {@link #fromPromptError(int)}，再看這次提示框按錯了幾次：有些裝置在提示框裡鎖定時回
     * {@code ERROR_USER_CANCELED}（T-71 實機：Pixel 9 Pro、Android 17），和按返回鍵分不出來。按錯過才結束的
     * 「取消」當成沒成功（{@link #INTERRUPTED}，顯示原因）；按「使用主密碼」（{@code ERROR_NEGATIVE_BUTTON}）一律是取消。
     *
     * @param errorCode      錯誤碼
     * @param failedAttempts 這次提示框的 {@code onAuthenticationFailed} 次數
     * @return 原因
     */
    public static BioReason fromPromptError(int errorCode, int failedAttempts) {
        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED && failedAttempts > 0) {
            return INTERRUPTED;
        }
        switch (errorCode) {
            case BiometricPrompt.ERROR_LOCKOUT:
                return LOCKOUT;
            case BiometricPrompt.ERROR_LOCKOUT_PERMANENT:
                return LOCKOUT_PERMANENT;
            case BiometricPrompt.ERROR_USER_CANCELED:
            case BiometricPrompt.ERROR_NEGATIVE_BUTTON:
                return CANCELED;
            case BiometricPrompt.ERROR_CANCELED:
                return INTERRUPTED;
            case BiometricPrompt.ERROR_NO_BIOMETRICS:
                return NONE_ENROLLED;
            case BiometricPrompt.ERROR_HW_NOT_PRESENT:
                return NO_STRONG_BIOMETRIC;
            case BiometricPrompt.ERROR_HW_UNAVAILABLE:
            case BiometricPrompt.ERROR_SECURITY_UPDATE_REQUIRED:
                return HARDWARE_UNAVAILABLE;
            default:
                return FAILED;
        }
    }
}
