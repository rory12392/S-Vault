package app.svault.android.shell.entries;

import android.app.Activity;
import app.svault.android.R;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.Toasts;
import app.svault.core.session.KdfUpgrade;
import app.svault.core.session.VaultSession;
import app.svault.core.vault.KdfCost;
import app.svault.core.vault.VaultException;
import java.util.concurrent.Executor;

/**
 * 解鎖後回到首頁時，金鑰衍生偏弱就提示並可一鍵升級成 S-Vault 預設（L-4，D-187）。讀格式與升級都在背景執行緒：
 * 存檔中 {@code VaultSession} 被佔著，主執行緒不直接問它。結果用首頁的 snackbar（{@link EntriesModel#notice}）。
 */
final class WeakKdfCheck {

    private WeakKdfCheck() {
    }

    static void maybeShow(Activity activity, VaultSession session, Executor worker, EntriesModel model) {
        worker.execute(() -> {
            boolean weak;
            try {
                weak = session.isUnlocked() && KdfCost.isWeak(session.vault().format());
            } catch (RuntimeException e) {
                return; // 剛好上鎖：不提示
            }
            if (!weak) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                ConfirmDialogs.show(activity, activity.getString(R.string.weak_kdf_title),
                        activity.getString(R.string.weak_kdf_body), activity.getString(R.string.weak_kdf_ok), false,
                        (d, w) -> worker.execute(() -> upgrade(activity, session, model)));
            });
        });
    }

    private static void upgrade(Activity activity, VaultSession session, EntriesModel model) {
        boolean ok;
        try {
            // 以最後的格式判斷成功：遠端剛被改過時升級會被合併蓋掉（Codex B3）
            ok = KdfUpgrade.run(session);
        } catch (VaultException | RuntimeException e) {
            ok = false;
        }
        boolean done = ok;
        activity.runOnUiThread(() -> model.notice(activity.getString(done ? R.string.weak_kdf_done
                : R.string.weak_kdf_failed), done ? Toasts.Kind.SUCCESS : Toasts.Kind.ERROR));
    }
}
