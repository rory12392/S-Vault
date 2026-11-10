package app.svault.desktop.shell.start;

import app.svault.core.session.KdfUpgrade;
import app.svault.core.session.VaultSession;
import app.svault.core.vault.KdfCost;
import app.svault.desktop.shell.AppShell;
import app.svault.desktop.shell.VaultWorker;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.Toast;
import java.util.ResourceBundle;

/**
 * 解鎖後，金鑰衍生偏弱時提示並可一鍵升級成 S-Vault 預設（L-4，D-187）。讀格式與升級都在背景執行緒：同步中
 * {@code VaultSession} 被佔著，JavaFX 執行緒不直接問它。
 */
final class WeakKdfPrompt {

    private WeakKdfPrompt() {
    }

    static void maybeShow(AppShell shell, VaultWorker worker, VaultSession session, ResourceBundle text) {
        worker.supply(() -> session.isUnlocked() && KdfCost.isWeak(session.vault().format()), weak -> {
            if (!weak) {
                return;
            }
            ConfirmDialog dialog = new ConfirmDialog(shell, text.getString("weakKdf.title"),
                    text.getString("weakKdf.body"), text.getString("weakKdf.ok"), ConfirmDialog.Variant.DEFAULT);
            dialog.dialog().getDialogPane().setId("weak-kdf-confirm");
            if (dialog.showAndWait()) {
                // 以最後的格式判斷成功：遠端剛被改過時升級會被合併蓋掉（Codex B3）
                worker.supply(() -> KdfUpgrade.run(session),
                        done -> shell.toasts().show(done ? Toast.Kind.SUCCESS : Toast.Kind.ERROR,
                                text.getString(done ? "weakKdf.done" : "weakKdf.failed")),
                        e -> shell.toasts().show(Toast.Kind.ERROR, text.getString("weakKdf.failed")));
            }
        }, e -> {
            // 讀不到格式（例如剛好上鎖）就不提示
        });
    }
}
