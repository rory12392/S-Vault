package app.svault.desktop.shell.entries;

import app.svault.core.session.VaultSession;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.shell.VaultOps;
import app.svault.desktop.shell.VaultWorker;
import java.time.Clock;
import java.util.Objects;
import javafx.application.Platform;

/**
 * 把功能模組的保管庫存取接到每次解鎖的條目畫面（T-32 的唯讀查詢、T-33 的寫入）。整個程式一個，{@code DesktopModules.hosts}
 * 與 {@code StartFlow} 用同一個：解鎖（與還原後重新載入）時 {@link #open} 一份新的租約，上鎖時 {@link #revoke()}。
 *
 * <p>放在這個套件是因為寫入成功後要用 {@link VaultScreen} 的套件內方法重畫（它在行數上限附近）。
 */
public final class ModuleVaultLink {

    private final ModuleVaultAccess access;
    private final ModuleVaultWriter writer;

    /**
     * @param access 模組的唯讀保管庫
     * @param writer 模組的寫入入口
     */
    public ModuleVaultLink(ModuleVaultAccess access, ModuleVaultWriter writer) {
        this.access = Objects.requireNonNull(access, "access");
        this.writer = Objects.requireNonNull(writer, "writer");
    }

    /** @return 模組的唯讀保管庫（交給 {@code DesktopModules.hosts}） */
    public ModuleVaultAccess access() {
        return access;
    }

    /** @return 模組的寫入入口（交給 {@code DesktopModules.hosts}） */
    public ModuleVaultWriter writer() {
        return writer;
    }

    /**
     * 顯示三欄時（{@link VaultScreen#show()} 之前，JavaFX 執行緒）：開一份新的租約，舊的作廢、等待中的通知移過來；
     * 條目畫面每次換新的快照都發布進去；寫入接上這次的保管庫，成功後回到 JavaFX 執行緒重畫三欄與同步狀態。
     *
     * @param screen  這次解鎖的條目畫面
     * @param worker  保管庫的背景執行緒
     * @param session 解鎖狀態
     * @param clock   修改時間的來源
     */
    public void open(VaultScreen screen, VaultWorker worker, VaultSession session, Clock clock) {
        ModuleVaultAccess.Lease lease = access.open();
        screen.publishTo(lease::publish);
        writer.attach(lease, new ModuleVaultWriter.Target(session, worker::execute, clock,
                out -> Platform.runLater(() -> showSaved(screen, out))));
    }

    /** 上鎖（按下上鎖當下與 session 的上鎖通知；任何執行緒）：模組已拿到的查詢失效，寫入不再可用。 */
    public void revoke() {
        access.revoke();
    }

    // JavaFX 執行緒。保管庫執行緒只有一條、JavaFX 依序處理，所以比這次寫入早排的操作會先重畫，不會蓋回舊內容
    private static void showSaved(VaultScreen screen, VaultOps.Outcome out) {
        if (screen.data() == null) {
            // 上鎖後已清掉（這個畫面已換掉），或第一次載入失敗：不重畫。第一次載入一定比寫入早排，結果也比這裡早回來
            return;
        }
        screen.refresh(out.data(), screen.currentEntryId());
        screen.sync().show(out);
    }
}
