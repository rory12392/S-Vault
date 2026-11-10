package app.svault.android.feature.biometric;

import androidx.annotation.Nullable;
import app.svault.android.shell.ext.UnlockProvider;
import javax.crypto.Cipher;

/**
 * 解鎖畫面按下「使用生物辨識解鎖」後的流程（T-71；AC-11-2～AC-11-4）：確認裝置可用 → {@link BioVault#prepareUnlock}
 * → 生物辨識提示框 → {@link BioVault#completeUnlock} → 主密碼交給外殼（外殼解鎖後清零，{@link UnlockProvider.Callback}
 * 的約定）。
 *
 * <p>任何失敗（裝置不支援、金鑰失效、連續失敗鎖定、意外的例外）都顯示原因並以 {@code onFailure} 退回主密碼輸入
 * （AC-11-4）；使用者自己按「使用主密碼」只回 {@code onCancel}，不另外顯示訊息。提示框的回呼不在外殼的錯誤邊界裡，
 * 所以這裡自己接住所有例外。和 Android 畫面無關的部分拆出來，好在 JVM 上測。
 */
final class BioUnlockFlow {

    /** 顯示生物辨識提示框（正式程式是 {@link BioPrompt#authenticate}）。 */
    interface Prompter {
        void authenticate(Cipher cipher, BioPrompt.Callback callback);
    }

    /** 顯示失敗原因（正式程式是解鎖畫面上的 error Toast）。 */
    interface Notice {
        void show(BioReason reason);
    }

    private final BioVault vault;
    @Nullable
    private final BioReason unavailable;
    private final Prompter prompter;
    private final Notice notice;

    /**
     * @param vault       金鑰與密文
     * @param unavailable 裝置不能用 Class 3 生物辨識的原因（{@link BioPrompt#strongUnavailableReason}）；可用時為 {@code null}
     * @param prompter    提示框
     * @param notice      顯示原因
     */
    BioUnlockFlow(BioVault vault, @Nullable BioReason unavailable, Prompter prompter, Notice notice) {
        this.vault = vault;
        this.unavailable = unavailable;
        this.prompter = prompter;
        this.notice = notice;
    }

    /**
     * @param vaultId  保管庫 ID
     * @param callback 外殼的回呼
     */
    void start(String vaultId, UnlockProvider.Callback callback) {
        if (unavailable != null) {
            fail(callback, new BioException(unavailable));
            return;
        }
        try {
            Cipher cipher = prepare(vaultId);
            prompter.authenticate(cipher, new BioPrompt.Callback() {
                @Override
                public void onAuthenticated(Cipher authenticated) {
                    finish(vaultId, authenticated, callback);
                }

                @Override
                public void onError(BioException error) {
                    if (error.reason() == BioReason.CANCELED) {
                        callback.onCancel();
                    } else {
                        fail(callback, error);
                    }
                }
            });
        } catch (BioException e) {
            fail(callback, e);
        } catch (RuntimeException e) {
            fail(callback, new BioException(BioReason.FAILED, e));
        }
    }

    private Cipher prepare(String vaultId) throws BioException {
        try {
            return vault.prepareUnlock(vaultId);
        } catch (BioException e) {
            if (e.reason() == BioReason.NOT_ENABLED) {
                // 有金鑰才會出現按鈕：沒有密文代表啟用做到一半，金鑰留著沒有用，清掉免得按鈕一直出現
                disableQuietly(vaultId);
            }
            throw e;
        }
    }

    private void finish(String vaultId, Cipher authenticated, UnlockProvider.Callback callback) {
        byte[] password;
        try {
            password = vault.completeUnlock(vaultId, authenticated);
        } catch (BioException e) {
            fail(callback, e);
            return;
        } catch (RuntimeException e) {
            fail(callback, new BioException(BioReason.FAILED, e));
            return;
        }
        // 主密碼明文只在這裡交出去；陣列由外殼清零（UnlockProvider.Callback 的約定），本類別不保留
        callback.onSuccess(password);
    }

    private void fail(UnlockProvider.Callback callback, BioException error) {
        try {
            notice.show(error.reason());
        } finally {
            callback.onFailure(error);
        }
    }

    private void disableQuietly(String vaultId) {
        try {
            vault.disable(vaultId);
        } catch (BioException | RuntimeException ignored) {
            // 已在回報 NOT_ENABLED；清不掉的金鑰在下次啟用時會被取代
        }
    }
}
