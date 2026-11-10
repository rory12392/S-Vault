package app.svault.android.feature.biometric;

import android.view.View;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.R;
import app.svault.android.shell.ext.UnlockProvider;
import app.svault.android.ui.Toasts;

/**
 * 解鎖畫面的「使用生物辨識解鎖」（T-71；AC-11-1 的解鎖部分、AC-11-4）。這個保管庫在 Keystore 有金鑰時才顯示；
 * 流程在 {@link BioUnlockFlow}，失敗原因用 error Toast 顯示在解鎖畫面上（外殼只負責退回主密碼輸入）。
 */
final class BioUnlockProvider implements UnlockProvider {

    private final BioKeys keys;

    /**
     * @param keys 金鑰庫（{@link #isAvailable} 拿不到 Context，只能查 Keystore）
     */
    BioUnlockProvider(BioKeys keys) {
        this.keys = keys;
    }

    @Override
    public int labelRes() {
        return R.string.bio_unlock_button;
    }

    @Override
    public boolean isAvailable(String vaultId) {
        return BioVault.hasKey(keys, vaultId);
    }

    @Override
    public void start(FragmentActivity activity, String vaultId, Callback callback) {
        View root = activity.findViewById(android.R.id.content);
        new BioUnlockFlow(BioVault.of(activity), BioPrompt.strongUnavailableReason(activity),
                (cipher, cb) -> BioPrompt.authenticate(activity, cipher, R.string.bio_prompt_title_unlock, cb),
                reason -> Toasts.show(root, activity.getString(reason.messageRes()), Toasts.Kind.ERROR))
                .start(vaultId, callback);
    }
}
