package app.svault.desktop.shell.start;

import app.svault.core.shell.Secrets;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.CorruptBackupException;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.CostlyKdfException;
import app.svault.core.vault.KdfCost;
import app.svault.core.vault.WrongPasswordException;
import app.svault.desktop.shell.AppShell;
import app.svault.desktop.shell.VaultWorker;
import app.svault.desktop.shell.entries.VaultScreen;
import app.svault.desktop.ui.ConfirmDialog;
import app.svault.desktop.ui.Toast;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.scene.control.MenuItem;

/**
 * 備份還原的流程（US-09）。兩個入口：
 * <ul>
 *   <li>解鎖中：工具列選單「備份還原」→ {@link BackupDialog} → 確認 → ProgressOverlay「正在還原…」→
 *       開啟中的保管庫換成還原的版本（{@code VaultSession.restore}），三欄重新載入，Toast「已還原」（AC-09-2）。</li>
 *   <li>上鎖中（檔案損壞，AC-09-5）：解鎖畫面的「從備份還原」→ {@link BackupDialog} → 確認 → 回到解鎖畫面輸入主密碼，
 *       「還原並解鎖」先用主密碼解開選定的備份，確認可用才寫回並直接解鎖；主密碼錯誤時什麼都不寫。</li>
 * </ul>
 * 讀備份清單與還原都在 {@link VaultWorker} 的背景執行緒（和存檔、上鎖同一條，彼此排隊）。
 */
final class RestoreFlow {

    private static final Logger LOG = Logger.getLogger(RestoreFlow.class.getName());

    /** 這台 PC 跑一次預設參數（Argon2d 64 MiB、2 次）大約幾秒，只用來顯示預估時間（C-4）。 */
    private static final double BASELINE_SECONDS = 0.5;

    private final AppShell shell;
    private final VaultWorker worker;
    private final VaultActions actions;
    private final ResourceBundle text;
    private final Supplier<VaultScreen> openScreen;
    private final Runnable reload;

    /**
     * @param shell      主視窗外框（進度、Toast、對話框的擁有者）
     * @param worker     保管庫的背景執行緒
     * @param actions    背後的操作
     * @param text       文字
     * @param openScreen 目前的三欄（還原前把沒寫進檔案的修改存一次）；上鎖時 {@code null}
     * @param reload     還原或還原並解鎖成功後，以新的內容顯示三欄
     */
    RestoreFlow(AppShell shell, VaultWorker worker, VaultActions actions, ResourceBundle text,
            Supplier<VaultScreen> openScreen, Runnable reload) {
        this.shell = shell;
        this.worker = worker;
        this.actions = actions;
        this.text = text;
        this.openScreen = openScreen;
        this.reload = reload;
    }

    /** @return 工具列選單的「備份還原」 */
    MenuItem menuItem() {
        MenuItem item = new MenuItem(text.getString("restore.menu"));
        item.setId("menu-restore");
        item.setOnAction(e -> openFromVault());
        return item;
    }

    // ---------- 解鎖中 ----------

    /** 列出開啟中保管庫的備份，選定並確認後還原。 */
    void openFromVault() {
        worker.supply(actions::backups, this::chooseAndRestore, this::listFailed);
    }

    private void chooseAndRestore(List<BackupStore.Backup> backups) {
        new BackupDialog(shell, text, backups).showAndWait().ifPresent(chosen -> {
            shell.progress().show(text.getString("restore.progress"));
            worker.run(() -> {
                // 上一次存檔失敗而留在記憶體的修改：先試著寫進檔案，讓「還原前的備份」包含它（同上鎖前，AC-12-3）
                VaultScreen screen = openScreen.get();
                if (screen != null) {
                    screen.saveBeforeLock();
                }
                actions.restore(chosen);
            }, () -> {
                shell.progress().hide();
                reload.run();
                shell.toasts().show(Toast.Kind.SUCCESS, text.getString("restore.done"));
            }, e -> {
                shell.progress().hide();
                shell.toasts().show(Toast.Kind.ERROR, text.getString(failureKey(e)));
            });
        });
    }

    // ---------- 上鎖中（檔案損壞） ----------

    /**
     * 一般解鎖失敗的呈現（AC-01-3、AC-01-7、AC-06-4）。檔案損壞時，這個檔案在本機有備份就附「從備份還原」，
     * 沒有時附「重新選擇檔案」（AC-09-5、Architecture §5）；查備份要讀資料夾，所以在背景執行緒。
     *
     * @param screen 解鎖畫面
     * @param error  開啟時的例外
     */
    void showUnlockFailure(UnlockScreen screen, Exception error, Runnable unlockConfirmed) {
        if (error instanceof CostlyKdfException costly) {
            // C-4（D-186）：成本超過門檻時先說明預估時間，使用者同意才真的跑金鑰衍生；主密碼欄位保留，不必再輸入
            long seconds = Math.max(1, Math.round(KdfCost.estimatedSeconds(costly.factor(), BASELINE_SECONDS)));
            ConfirmDialog dialog = new ConfirmDialog(screen, text.getString("unlock.costly.title"),
                    MessageFormat.format(text.getString("unlock.costly.body"), seconds, Math.round(costly.factor())),
                    text.getString("unlock.costly.ok"), ConfirmDialog.Variant.DEFAULT);
            dialog.dialog().getDialogPane().setId("unlock-costly-confirm");
            if (dialog.showAndWait()) {
                unlockConfirmed.run();
            }
            return;
        }
        OpenProblem problem = OpenProblem.of(error);
        if (problem.fieldError()) {
            screen.showFieldError(problem.messageKey());
            return;
        }
        if (!(error instanceof CorruptVaultException)) {
            screen.showBanner(problem);
            return;
        }
        Path file = screen.file();
        worker.supply(() -> !actions.backupsOf(file).isEmpty(),
                has -> screen.showBanner(has ? OpenProblem.CORRUPT_RESTORABLE : problem),
                e -> screen.showBanner(problem));
    }

    /**
     * 解鎖畫面的「從備份還原」「選擇其他版本」：選定並確認後，解鎖畫面改成「還原並解鎖」。
     *
     * @param screen 解鎖畫面
     */
    void chooseForUnlock(UnlockScreen screen) {
        Path file = screen.file();
        worker.supply(() -> actions.backupsOf(file), backups -> new BackupDialog(screen, text, backups)
                .showAndWait()
                .ifPresent(chosen -> screen.showRestore(chosen, chosen.time().format(BackupDialog.TIME))),
                this::listFailed);
    }

    /**
     * 「還原並解鎖」：主密碼錯誤時欄位下方「主密碼錯誤」（保留還原模式）；備份不能用或寫不進去時 error Banner＋
     * 「選擇其他版本」。
     *
     * @param screen 解鎖畫面（已選定要還原的備份）
     */
    void restoreAndUnlock(UnlockScreen screen) {
        BackupStore.Backup chosen = screen.restoreTarget();
        char[] chars = screen.password();
        byte[] pw;
        try {
            pw = Secrets.utf8(chars);
        } finally {
            Secrets.wipe(chars);
        }
        Path file = screen.file();
        screen.setBusy(true);
        shell.progress().show(text.getString("restore.progress"));
        worker.run(() -> {
            try {
                actions.restoreAndUnlock(file, chosen, pw);
            } finally {
                Secrets.wipe(pw);
            }
        }, () -> {
            shell.progress().hide();
            screen.clearPassword();
            reload.run();
            shell.toasts().show(Toast.Kind.SUCCESS, text.getString("restore.done"));
        }, e -> {
            shell.progress().hide();
            screen.setBusy(false);
            if (e instanceof WrongPasswordException) {
                screen.showFieldError("unlock.error.wrong");
            } else {
                screen.showRestoreProblem(failureKey(e));
            }
        });
    }

    private static String failureKey(Exception e) {
        // 只記類型；訊息與堆疊不顯示也不寫日誌（可能含路徑以外的資訊）
        LOG.log(Level.WARNING, "還原失敗：{0}", e.getClass().getName());
        return e instanceof CorruptBackupException ? "restore.error.backup" : "restore.error.failed";
    }

    private void listFailed(Exception e) {
        LOG.log(Level.WARNING, "無法列出備份：{0}", e.getClass().getName());
        shell.toasts().show(Toast.Kind.ERROR, text.getString("restore.error.list"));
    }
}
