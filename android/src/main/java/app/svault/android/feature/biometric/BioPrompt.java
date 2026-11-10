package app.svault.android.feature.biometric;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.R;
import javax.crypto.Cipher;

/**
 * 用 {@code BiometricPrompt}＋{@code CryptoObject} 讓使用者以 Class 3 生物辨識解開 {@link BioVault} 的加密器
 * （AC-11-2、AC-11-4）。只接受 {@code BIOMETRIC_STRONG}，不提供裝置 PIN 代替，負面按鈕是「使用主密碼」。
 */
public final class BioPrompt {

    private static final String TAG = "SVault";

    /** 結果回呼，在 UI 執行緒呼叫。 */
    public interface Callback {

        /** @param cipher 通過生物辨識、可以 {@code doFinal} 的加密器 */
        void onAuthenticated(Cipher cipher);

        /** @param error 失敗原因（取消、連續失敗鎖定、硬體問題等，AC-11-4） */
        void onError(BioException error);
    }

    private BioPrompt() {
    }

    /**
     * 查詢裝置能不能用 Class 3 生物辨識（AC-11-4：只有 Class 2 以下時退回主密碼並顯示原因）。
     *
     * @param context 任何 Context
     * @return 不能用的原因；可以用時為 {@code null}
     */
    public static BioReason strongUnavailableReason(Context context) {
        int status = BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return BioReason.fromCanAuthenticate(status);
    }

    /**
     * 顯示生物辨識提示框。
     *
     * @param activity 目前的畫面
     * @param cipher   {@link BioVault#prepareEnable} 或 {@link BioVault#prepareUnlock} 回傳的加密器
     * @param titleRes 標題
     * @param callback 結果
     */
    public static void authenticate(FragmentActivity activity, Cipher cipher, @StringRes int titleRes,
            Callback callback) {
        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(titleRes))
                .setNegativeButtonText(activity.getString(R.string.bio_prompt_use_password))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .build();
        BiometricPrompt prompt = new BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    private int failed;

                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        BiometricPrompt.CryptoObject crypto = result.getCryptoObject();
                        Cipher authenticated = crypto == null ? null : crypto.getCipher();
                        if (authenticated == null) {
                            callback.onError(new BioException(BioReason.FAILED));
                        } else {
                            callback.onAuthenticated(authenticated);
                        }
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        // errString 是系統訊息，不含機密；仍只回報對應的原因，畫面文字一律用本模組的字串。
                        // 錯誤碼記日誌：各廠牌在鎖定、取消時回的碼不一樣（T-71 實機），排查要看
                        Log.i(TAG, "生物辨識提示框錯誤碼：" + errorCode);
                        callback.onError(new BioException(BioReason.fromPromptError(errorCode, failed)));
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // 單次比對失敗：提示框留著讓使用者再試；記下次數，鎖定時有些裝置只回「取消」（BioReason）
                        failed++;
                    }
                });
        prompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
    }
}
