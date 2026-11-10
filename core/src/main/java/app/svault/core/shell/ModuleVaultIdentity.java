package app.svault.core.shell;

import app.svault.core.module.MasterPasswordCheck;
import app.svault.core.module.MasterPasswordCheck.Status;
import app.svault.core.session.VaultSession;
import app.svault.core.vault.MasterPasswordProbe;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 給功能模組的「這次解鎖是哪個保管庫」與「輸入的是不是它的主密碼」（T-35，BIO 的啟用；android
 * {@code ModuleHost.unlockedVaultId()}、{@code verifyMasterPassword} 委派到這裡）。整個程式一個；外殼在每次解鎖（或還原後）
 * {@link #attach} 這次解鎖的租約與保管庫執行緒，和唯讀查詢、寫入是同一份租約。
 *
 * <p><b>不交出、不保留主密碼</b>（D-165）：模組交來的主密碼在呼叫的執行緒當場由 {@code core.vault} 算成複合金鑰
 * （{@link MasterPasswordProbe}），呼叫回傳後模組就可以清零；複合金鑰在保管庫執行緒和開啟中保管庫的比對完立即清零。模組只拿到
 * 「相符／不符」與保管庫 ID，拿不到 {@code Vault}、金鑰或外殼解鎖時的主密碼。不重算 KDF（複合金鑰就是 KDF 的輸入）。
 *
 * <p><b>上鎖時不可用</b>：按下上鎖當下、還原備份後舊的租約作廢，保管庫 ID 為空、確認一律 {@link Status#LOCKED}。
 *
 * <p><b>次數上限</b>：同一次解鎖錯 {@link #MAX_MISMATCHES} 次之後一律 {@link Status#TOO_MANY_ATTEMPTS}，不再比對；要上鎖
 * 再用主密碼解鎖才重算。比對不跑 KDF、幾乎不花時間，沒有上限的話拿到解鎖中手機的人能用它比解鎖畫面
 * （每次一次 KDF）更快地猜主密碼；有了上限，不知道主密碼的人重新解鎖不了，總共只有這幾次。
 *
 * <p><b>開放範圍</b>：保管庫 ID 不是機密（{@code recent.json} 也有），所有模組都能取；確認主密碼只開放給
 * {@link #VERIFY_MODULES}，其他模組呼叫時丟 {@link SecurityException}。每次確認記錄模組代號與結果（不記主密碼與保管庫 ID）。
 */
public final class ModuleVaultIdentity {

    /** 能確認主密碼的模組（D-165）：只有生物辨識（BIO）。要開放給其他模組時另開核心任務，並檢視威脅模型與防護。 */
    public static final Set<String> VERIFY_MODULES = Set.of("biometric");

    /** 同一次解鎖最多錯幾次。 */
    public static final int MAX_MISMATCHES = 5;

    private static final Logger LOG = Logger.getLogger(ModuleVaultIdentity.class.getName());

    /**
     * 一次解鎖的保管庫。
     *
     * @param session     解鎖狀態
     * @param vaultThread 保管庫的背景執行緒（android {@code worker()}；和存檔、上鎖同一條，比對時保管庫不會被關掉）
     */
    public record Target(VaultSession session, Executor vaultThread) {

        /** 參數都不能是 {@code null}。 */
        public Target {
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(vaultThread, "vaultThread");
        }
    }

    private record Binding(ModuleVaultAccess.Lease lease, Target target, String vaultId, AtomicInteger mismatches) { }

    private volatile Binding binding;

    /**
     * 解鎖（或還原後）時呼叫，和 {@link ModuleVaultAccess#open()} 同一份租約；租約作廢後就不可用，不用另外解除。在解鎖通知
     * 裡或保管庫執行緒呼叫（要讀一次開啟中保管庫的 ID）。session 已經上鎖時不接上（之後一律當作上鎖）。
     *
     * @param lease  這次解鎖的租約
     * @param target 這次解鎖的保管庫
     */
    public void attach(ModuleVaultAccess.Lease lease, Target target) {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(target, "target");
        String id;
        try {
            // 保管庫 ID＝根群組 UUID（Architecture §2.1）；BIO 的 Keystore 別名、recent.json 用的是同一個
            id = target.session().vault().id().toString();
        } catch (IllegalStateException e) {
            binding = null;
            return;
        }
        binding = new Binding(lease, target, id, new AtomicInteger());
    }

    /**
     * 任何執行緒都可以呼叫，不做 I/O。
     *
     * @return 目前解鎖的保管庫 ID；上鎖（含按下上鎖當下）、還沒解鎖過時為空
     */
    public Optional<String> vaultId() {
        Binding b = binding;
        if (b == null || !b.lease().active() || !b.target().session().isUnlocked()) {
            return Optional.empty();
        }
        return Optional.of(b.vaultId());
    }

    /**
     * 確認 {@code password} 是不是目前解鎖的保管庫的主密碼：在呼叫的執行緒算好複合金鑰，排進保管庫執行緒比對，結果交給
     * {@code callback}（一定剛好一次，包含已上鎖）。任何執行緒都可以呼叫，不等比對。
     *
     * @param moduleId 呼叫端模組的代號（只傳自己的）
     * @param password 使用者輸入的主密碼（UTF-8）；不會被保留，呼叫回傳後呼叫端就可以清零（或繼續用，用完自己清零）
     * @param executor 執行 {@code callback} 的地方（畫面傳 UI 執行緒）
     * @param callback 結果（模組自己包 {@code ModuleGuard}；丟出的例外只記錄類型）
     * @throws SecurityException    模組不在 {@link #VERIFY_MODULES}
     * @throws NullPointerException 參數是 {@code null}
     */
    public void verifyMasterPassword(String moduleId, byte[] password, Executor executor,
                                     Consumer<MasterPasswordCheck> callback) {
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(callback, "callback");
        // Set.of 的 contains(null) 會丟 NullPointerException
        if (moduleId == null || !VERIFY_MODULES.contains(moduleId)) {
            LOG.log(Level.WARNING, "模組確認主密碼被拒（不在開放範圍）：{0}", String.valueOf(moduleId));
            throw new SecurityException("這個模組不能確認主密碼：" + moduleId);
        }
        Binding b = binding;
        if (b == null || !b.lease().active()) {
            finish(moduleId, MasterPasswordCheck.rejected(Status.LOCKED), executor, callback);
            return;
        }
        MasterPasswordProbe probe = MasterPasswordProbe.of(password);
        try {
            b.target().vaultThread().execute(() -> {
                MasterPasswordCheck result;
                try {
                    result = check(b, probe);
                } finally {
                    probe.close();
                }
                finish(moduleId, result, executor, callback);
            });
        } catch (RejectedExecutionException e) {
            // 程式正在結束
            probe.close();
            finish(moduleId, MasterPasswordCheck.rejected(Status.LOCKED), executor, callback);
        }
    }

    // 保管庫執行緒
    private static MasterPasswordCheck check(Binding b, MasterPasswordProbe probe) {
        VaultSession session = b.target().session();
        if (!b.lease().active() || !session.isUnlocked()) {
            return MasterPasswordCheck.rejected(Status.LOCKED);
        }
        if (b.mismatches().get() >= MAX_MISMATCHES) {
            return MasterPasswordCheck.rejected(Status.TOO_MANY_ATTEMPTS);
        }
        try {
            if (probe.matches(session.vault())) {
                return MasterPasswordCheck.matched(b.vaultId());
            }
            b.mismatches().incrementAndGet();
            return MasterPasswordCheck.rejected(Status.MISMATCH);
        } catch (IllegalStateException e) {
            // 比對途中上鎖（例如整個程式結束時）
            return MasterPasswordCheck.rejected(session.isUnlocked() ? Status.FAILED : Status.LOCKED);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "模組確認主密碼失敗：{0}", e.getClass().getName());
            return MasterPasswordCheck.rejected(Status.FAILED);
        }
    }

    private static void finish(String moduleId, MasterPasswordCheck result, Executor executor,
                               Consumer<MasterPasswordCheck> callback) {
        // 只記代號與結果，不記主密碼、長度或保管庫 ID（CONVENTIONS §3）
        LOG.log(Level.INFO, "模組確認主密碼：{0} {1}", new Object[] {moduleId, result.status()});
        try {
            executor.execute(() -> {
                try {
                    callback.accept(result);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "模組確認主密碼的結果處理失敗：{0} {1}",
                            new Object[] {moduleId, e.getClass().getName()});
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.log(Level.WARNING, "模組確認主密碼的結果送不出去：{0}", moduleId);
        }
    }
}
