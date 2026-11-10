package app.svault.android.shell.entries;

import app.svault.core.session.VaultSession;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultIdentity;
import app.svault.core.shell.ModuleVaultWriter;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * 功能模組的「這次解鎖」：唯讀查詢（T-31）、寫入（T-33）、保管庫 ID 與確認主密碼（T-35）綁在同一份租約，解鎖或還原後
 * {@link #open}、上鎖時 {@link #revoke}。整個 App 一個，由 {@code SvaultApplication} 持有（T-35 從它拆出，行數上限）。
 */
public final class ModuleLeases {

    private final ModuleVaultAccess access;
    private final ModuleVaultWriter writer = new ModuleVaultWriter();
    private final ModuleVaultIdentity identity = new ModuleVaultIdentity();
    private final Executor main;
    private final Supplier<EntriesModel> current;
    private volatile ModuleVaultAccess.Lease lease;

    /**
     * @param loader  模組要內容但還沒載入時（任何執行緒；{@link ModuleVaultAccess} 的 loader）
     * @param main    主執行緒
     * @param current 目前這次解鎖的條目狀態；沒有時為 {@code null}（不建立新的）
     */
    public ModuleLeases(Runnable loader, Executor main, Supplier<EntriesModel> current) {
        this.access = new ModuleVaultAccess(loader);
        this.main = main;
        this.current = current;
    }

    /**
     * 解鎖（或還原後）開一份新的租約，舊的作廢。在解鎖通知裡或保管庫執行緒呼叫（要讀一次開啟中保管庫的 ID）。
     *
     * <p>不拿外殼的鎖：解鎖通知是在 {@link VaultSession} 的鎖裡送來的，而 {@code SvaultApplication.entries()} 是先拿自己的鎖
     * 再問 session，兩邊都拿會死結。改成租約放 volatile 欄位、在主執行緒接上已經有的條目狀態；外殼建立條目狀態時也
     * {@link #feed} 一次，所以不論誰先，最後都接在最新的租約上（接兩次只是多發布一次）。
     *
     * @param session 解鎖狀態
     * @param worker  保管庫的背景執行緒
     * @param clock   修改時間的來源
     */
    public void open(VaultSession session, Executor worker, Clock clock) {
        ModuleVaultAccess.Lease opened = access.open();
        lease = opened;
        writer.attach(opened, new ModuleVaultWriter.Target(session, worker, clock,
                out -> ModuleEntriesFeed.deliver(current.get(), out)));
        identity.attach(opened, new ModuleVaultIdentity.Target(session, worker));
        main.execute(() -> {
            EntriesModel model = current.get();
            if (model != null) {
                feed(model, opened);
            }
        });
    }

    /** 上鎖（按下上鎖當下，不等背景存檔）或還原時沒有解鎖：模組手上的一切立即失效。任何執行緒。 */
    public void revoke() {
        access.revoke();
    }

    /**
     * 新建立的條目狀態接上目前的租約（主執行緒或持有外殼的鎖時都可以呼叫，實際註冊在主執行緒）。
     *
     * @param model 這次解鎖的條目狀態
     */
    public void feed(EntriesModel model) {
        feed(model, lease);
    }

    private void feed(EntriesModel model, ModuleVaultAccess.Lease target) {
        if (target == null) {
            return;
        }
        // LiveData 的觀察者只能在主執行緒註冊；已有內容時註冊當下就會發布
        main.execute(() -> {
            if (target.active()) {
                ModuleEntriesFeed.attach(model, target);
            }
        });
    }

    /** @return 唯讀查詢（{@code ModuleHost.unlockedEntries()}、{@code whenEntriesLoaded}） */
    public ModuleVaultAccess access() {
        return access;
    }

    /** @return 寫入入口（{@code ModuleHost.addEntries}） */
    public ModuleVaultWriter writer() {
        return writer;
    }

    /** @return 保管庫 ID 與確認主密碼（{@code ModuleHost.unlockedVaultId()}、{@code verifyMasterPassword}） */
    public ModuleVaultIdentity identity() {
        return identity;
    }
}
