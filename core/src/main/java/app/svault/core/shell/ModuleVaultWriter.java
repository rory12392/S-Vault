package app.svault.core.shell;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryBatchResult.Status;
import app.svault.core.session.VaultSession;
import app.svault.core.sync.SyncReport;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import java.time.Clock;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 功能模組寫入條目的入口（T-33，兩平台的 {@code ModuleHost.addEntries} 都委派到這裡）：在根群組底下新建一個群組、
 * 加入整批條目、存檔一次。整個程式一個；外殼在每次解鎖時 {@link #attach} 這次解鎖的租約與保管庫執行緒。
 *
 * <p><b>全部成功才算數</b>：經 {@link VaultSession#commit}，在複本上用 {@link VaultEditor} 加入，再走同一條存檔流程
 * （遠端有變更先三方合併，再經 {@code SaveCoordinator}：備份 → 暫存 → 讀回驗證 → 取代）。存檔失敗、合併有衝突或任何一筆
 * 加不進去時，開啟中的保管庫、{@code pending/} 與畫面都維持原狀。
 *
 * <p><b>上鎖時不可用</b>：可用與否綁在唯讀查詢的同一份租約（{@link ModuleVaultAccess.Lease}）：按下上鎖當下、還原備份後
 * 舊的租約作廢，之後的呼叫一律 {@link Status#LOCKED}。已排進保管庫執行緒的那一批照常完成（上鎖排在它後面，同編輯畫面的
 * 存檔）。
 *
 * <p><b>開放範圍</b>（D-162）：只有 {@link #ALLOWED_MODULES} 裡的模組能用，其他模組呼叫時丟 {@link SecurityException}；
 * 每次呼叫記錄模組代號、筆數與結果（不記任何內容）。
 */
public final class ModuleVaultWriter {

    /** 能寫入的模組（D-162）：只有匯入（IMP）。要開放給其他模組時另開核心任務，並檢視威脅模型與防護。 */
    public static final Set<String> ALLOWED_MODULES = Set.of("importer");

    private static final Logger LOG = Logger.getLogger(ModuleVaultWriter.class.getName());

    /**
     * 一次解鎖的保管庫與畫面。
     *
     * @param session     解鎖狀態
     * @param vaultThread 保管庫的背景執行緒（desktop {@code VaultWorker::execute}、android {@code worker()}；和存檔、上鎖同一條）
     * @param clock       修改時間的來源
     * @param onSaved     存檔成功後（保管庫執行緒）：外殼以新的內容重畫條目畫面與同步狀態（{@link VaultOps.Outcome#data()}
     *                    是新的內容、{@code subject} 是新群組）
     */
    public record Target(VaultSession session, Executor vaultThread, Clock clock, Consumer<VaultOps.Outcome> onSaved) {

        /** 參數都不能是 {@code null}。 */
        public Target {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(vaultThread, "vaultThread");
            Objects.requireNonNull(clock, "clock");
            Objects.requireNonNull(onSaved, "onSaved");
        }
    }

    private record Binding(ModuleVaultAccess.Lease lease, Target target) { }

    private volatile Binding binding;

    /**
     * 解鎖（或還原後）時呼叫，和 {@link ModuleVaultAccess#open()} 同一份租約；租約作廢後寫入就不可用，不用另外解除。
     * 任何執行緒都可以呼叫。
     *
     * @param lease  這次解鎖的租約
     * @param target 這次解鎖的保管庫與畫面
     */
    public void attach(ModuleVaultAccess.Lease lease, Target target) {
        binding = new Binding(Objects.requireNonNull(lease, "lease"), Objects.requireNonNull(target, "target"));
    }

    /**
     * 排進保管庫執行緒：新建群組、加入整批條目、存檔一次，結果交給 {@code callback}（一定剛好一次，包含已上鎖）。
     * 任何執行緒都可以呼叫，不等存檔。
     *
     * @param moduleId 呼叫端模組的代號（只傳自己的；desktop 的 host 自己帶）
     * @param batch    要加入的條目
     * @param executor 執行 {@code callback} 的地方（畫面傳 UI 執行緒）
     * @param callback 結果（模組自己包 {@code ModuleGuard}；丟出的例外只記錄類型）
     * @throws SecurityException 模組不在 {@link #ALLOWED_MODULES}
     */
    public void addEntries(String moduleId, EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(callback, "callback");
        // Set.of 的 contains(null) 會丟 NullPointerException
        if (moduleId == null || !ALLOWED_MODULES.contains(moduleId)) {
            LOG.log(Level.WARNING, "模組寫入被拒（不在開放範圍）：{0}", String.valueOf(moduleId));
            throw new SecurityException("這個模組不能寫入保管庫：" + moduleId);
        }
        Binding b = binding;
        if (b == null || !b.lease().active()) {
            finish(moduleId, batch, EntryBatchResult.rejected(Status.LOCKED), executor, callback);
            return;
        }
        try {
            b.target().vaultThread().execute(
                    () -> finish(moduleId, batch, write(b, batch), executor, callback));
        } catch (RejectedExecutionException e) {
            // 程式正在結束
            finish(moduleId, batch, EntryBatchResult.rejected(Status.LOCKED), executor, callback);
        }
    }

    // 保管庫執行緒
    private static EntryBatchResult write(Binding b, EntryBatch batch) {
        VaultSession session = b.target().session();
        if (!b.lease().active() || !session.isUnlocked()) {
            return EntryBatchResult.rejected(Status.LOCKED);
        }
        if (session.conflict().isPresent()) {
            return EntryBatchResult.rejected(Status.CONFLICT);
        }
        UUID[] group = new UUID[1];
        SyncReport report;
        try {
            report = session.commit(vault -> {
                VaultEditor editor = new VaultEditor(vault, b.target().clock());
                // 保管庫 ID＝根群組 UUID（Architecture §2.1）
                group[0] = editor.addGroup(vault.id(), batch.groupName());
                for (EntryDraft draft : batch.entries()) {
                    editor.addEntry(group[0], draft);
                }
            });
        } catch (VaultException e) {
            LOG.log(Level.WARNING, "模組寫入的存檔失敗：{0}", e.getClass().getName());
            return EntryBatchResult.rejected(Status.FAILED);
        } catch (IllegalStateException e) {
            // 已上鎖（例如整個程式結束時）或在檢查之後出現了衝突；其他原因當成失敗
            if (!session.isUnlocked()) {
                return EntryBatchResult.rejected(Status.LOCKED);
            }
            LOG.log(Level.WARNING, "模組寫入失敗：{0}", e.getClass().getName());
            return EntryBatchResult.rejected(session.conflict().isPresent() ? Status.CONFLICT : Status.FAILED);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "模組寫入失敗：{0}", e.getClass().getName());
            return EntryBatchResult.rejected(Status.FAILED);
        }
        if (report.hasConflicts()) {
            return EntryBatchResult.rejected(Status.CONFLICT);
        }
        try {
            b.target().onSaved().accept(
                    new VaultOps.Outcome(ScreenData.of(session.vault()), group[0], false, null, report));
        } catch (RuntimeException e) {
            // 已寫進檔案；畫面下次同步或重新載入時就會看到
            LOG.log(Level.WARNING, "模組寫入後重畫失敗：{0}", e.getClass().getName());
        }
        boolean merged = new VaultOps.Outcome(null, group[0], false, null, report).merged();
        return EntryBatchResult.saved(group[0], batch.size(), merged);
    }

    private static void finish(String moduleId, EntryBatch batch, EntryBatchResult result, Executor executor,
                               Consumer<EntryBatchResult> callback) {
        // 只記代號、筆數與結果，不記群組名稱或任何條目內容（CONVENTIONS §3）
        LOG.log(Level.INFO, "模組寫入：{0} {1} 筆 {2}",
                new Object[] {moduleId, Integer.toString(batch.size()), result.status()});
        try {
            executor.execute(() -> {
                try {
                    callback.accept(result);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "模組寫入的結果處理失敗：{0} {1}",
                            new Object[] {moduleId, e.getClass().getName()});
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "模組寫入的結果送不出去：{0}", moduleId);
        }
    }
}
