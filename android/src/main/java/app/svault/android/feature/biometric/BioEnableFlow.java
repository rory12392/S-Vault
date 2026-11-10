package app.svault.android.feature.biometric;

import androidx.annotation.Nullable;
import app.svault.core.module.MasterPasswordCheck;
import app.svault.core.shell.Secrets;
import java.util.function.Consumer;
import javax.crypto.Cipher;

/**
 * 設定頁按下「啟用生物辨識解鎖」並輸入主密碼後的流程（T-72；AC-11-1 的啟用、AC-11-2、AC-11-4）：外殼確認主密碼
 * （{@code ModuleHost.verifyMasterPassword}，D-165）→ {@link BioVault#prepareEnable} → 生物辨識提示框 →
 * {@link BioVault#completeEnable}。
 *
 * <p>主密碼位元組由本流程接手：每一條路徑（不符、上鎖、裝置不支援、提示框取消或失敗、意外的例外、成功）結束時都清零，
 * 不存進欄位以外的地方、不寫日誌。金鑰一律用確認結果帶的保管庫 ID 命名（確認到提示框之間換了保管庫也不會綁錯）。
 * 沒有成功時刪除這個保管庫的金鑰與密文：只有金鑰沒有密文時解鎖畫面會出現按鈕（{@link BioVault#hasKey}）。
 * 外殼的確認結果與提示框的回呼都不在外殼的錯誤邊界裡，所以這裡自己接住所有例外。和 Android 畫面無關，在 JVM 上測。
 */
final class BioEnableFlow {

    /** 確認主密碼（正式程式是 {@code ModuleHost.verifyMasterPassword}，結果在主執行緒）。 */
    interface Verifier {
        void verify(byte[] password, Consumer<MasterPasswordCheck> result);
    }

    /** 畫面要做的事；都在主執行緒呼叫，每次流程只會收到 {@link #onRejected}、{@link #onEnabled}、{@link #onCanceled}、
     * {@link #onFailed} 其中一個。 */
    interface Listener {

        /** 主密碼相符，接著出現提示框（輸入主密碼的對話框可以關了）。 */
        void onVerified();

        /** @param status 確認沒有通過（{@link MasterPasswordCheck.Status#MATCH} 以外），沒有出現提示框 */
        void onRejected(MasterPasswordCheck.Status status);

        /** @param vaultId 已啟用的保管庫 */
        void onEnabled(String vaultId);

        /** 使用者在提示框按「使用主密碼」或返回；沒有啟用。 */
        void onCanceled();

        /** @param reason 失敗原因（裝置不支援、連續失敗、金鑰庫錯誤等）；沒有啟用 */
        void onFailed(BioReason reason);
    }

    private final BioVault vault;
    @Nullable
    private final BioReason unavailable;
    private final Verifier verifier;
    private final BioUnlockFlow.Prompter prompter;
    private final Listener listener;

    /**
     * @param vault       金鑰與密文
     * @param unavailable 裝置不能用 Class 3 生物辨識的原因（{@link BioPrompt#strongUnavailableReason}）；可用時為 {@code null}
     * @param verifier    確認主密碼
     * @param prompter    提示框
     * @param listener    畫面
     */
    BioEnableFlow(BioVault vault, @Nullable BioReason unavailable, Verifier verifier,
            BioUnlockFlow.Prompter prompter, Listener listener) {
        this.vault = vault;
        this.unavailable = unavailable;
        this.verifier = verifier;
        this.prompter = prompter;
        this.listener = listener;
    }

    /**
     * @param password 使用者輸入的主密碼（UTF-8）；由本流程接手，結束時清零，呼叫端不要再用
     */
    void start(byte[] password) {
        if (unavailable != null) {
            Secrets.wipe(password);
            listener.onFailed(unavailable);
            return;
        }
        try {
            verifier.verify(password, check -> onChecked(password, check));
        } catch (RuntimeException e) {
            // 例如代號不對的 SecurityException：不會有結果，這裡結束
            Secrets.wipe(password);
            listener.onFailed(BioReason.FAILED);
        }
    }

    private void onChecked(byte[] password, MasterPasswordCheck check) {
        if (!check.matched()) {
            Secrets.wipe(password);
            listener.onRejected(check.status());
            return;
        }
        String vaultId = check.vaultId();
        listener.onVerified();
        Cipher cipher;
        try {
            cipher = vault.prepareEnable(vaultId);
        } catch (BioException e) {
            abort(vaultId, password, e.reason());
            return;
        } catch (RuntimeException e) {
            abort(vaultId, password, BioReason.FAILED);
            return;
        }
        try {
            prompter.authenticate(cipher, new BioPrompt.Callback() {
                @Override
                public void onAuthenticated(Cipher authenticated) {
                    finish(vaultId, authenticated, password);
                }

                @Override
                public void onError(BioException error) {
                    Secrets.wipe(password);
                    disableQuietly(vaultId);
                    if (error.reason() == BioReason.CANCELED) {
                        listener.onCanceled();
                    } else {
                        listener.onFailed(error.reason());
                    }
                }
            });
        } catch (RuntimeException e) {
            abort(vaultId, password, BioReason.FAILED);
        }
    }

    private void finish(String vaultId, Cipher authenticated, byte[] password) {
        try {
            vault.completeEnable(vaultId, authenticated, password);
        } catch (BioException e) {
            // completeEnable 失敗時已刪除金鑰
            Secrets.wipe(password);
            listener.onFailed(e.reason());
            return;
        } catch (RuntimeException e) {
            abort(vaultId, password, BioReason.FAILED);
            return;
        }
        Secrets.wipe(password);
        listener.onEnabled(vaultId);
    }

    private void abort(String vaultId, byte[] password, BioReason reason) {
        Secrets.wipe(password);
        disableQuietly(vaultId);
        listener.onFailed(reason);
    }

    private void disableQuietly(String vaultId) {
        try {
            vault.disable(vaultId);
        } catch (BioException | RuntimeException ignored) {
            // 密文一定已刪除（disable 先刪密文）；留下的金鑰在解鎖時由 BioUnlockFlow 清掉，或下次啟用時被取代
        }
    }
}
