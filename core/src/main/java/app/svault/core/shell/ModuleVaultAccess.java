package app.svault.core.shell;

import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 外殼把開啟中的保管庫以唯讀方式交給功能模組（T-31，Architecture §6）：每次解鎖開一份 {@link Lease}，外殼把條目畫面的
 * 快照（{@link ScreenData}，不可變）發布進來，模組經 {@link #unlockedEntries()} 拿到綁在這份租約上的
 * {@link UnlockedEntries}。上鎖時 {@link #revoke()}：模組手上已經拿到的物件之後一律回傳空的結果。
 *
 * <p>為什麼用畫面的快照、不另外讀 {@code Vault}：{@code Vault} 與 {@code VaultEditor} 不是執行緒安全的，只能在外殼的
 * 保管庫執行緒碰；快照在那條執行緒建好後才發布，任何執行緒都能安全地讀（AF 的服務回呼在主執行緒，之後的流程可能在背景）。
 * 模組因此拿不到寫入介面、金鑰與主密碼。
 *
 * <p>執行緒：所有方法都可以在任何執行緒呼叫。狀態轉換在本物件的鎖裡，查詢只讀 volatile 欄位，不等存檔或合併。
 */
public final class ModuleVaultAccess {

    private static final Logger LOG = Logger.getLogger(ModuleVaultAccess.class.getName());

    private final Runnable loader;
    // 只在鎖裡改；查詢不拿鎖，所以 volatile
    private volatile Lease current;

    /**
     * @param loader 解鎖中、內容還沒載入時請外殼開始載入（可能在任何執行緒呼叫、可能重複呼叫；外殼自己轉到 UI
     *               執行緒，已在載入或已載入時不做事）。載入完成後外殼呼叫 {@link Lease#publish}
     */
    public ModuleVaultAccess(Runnable loader) {
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    /**
     * 解鎖時（或還原後換了內容時）呼叫：之前的租約作廢，開一份新的（還沒有內容）。還在等內容的 {@link #whenLoaded}
     * 移到新的租約，等新的內容載入後通知（沒有上鎖，只是換了內容）。
     *
     * @return 新的租約，外殼把這次解鎖的快照發布到它
     */
    public Lease open() {
        Lease lease = new Lease();
        synchronized (this) {
            lease.pending.addAll(endCurrent());
            current = lease;
        }
        return lease;
    }

    /**
     * 上鎖時呼叫（可重複呼叫）：目前的租約作廢，模組已經拿到的 {@link UnlockedEntries} 之後回傳空的結果，
     * 還在等內容的 {@link #whenLoaded} 不會被呼叫。
     */
    public void revoke() {
        List<Pending> dropped;
        synchronized (this) {
            dropped = endCurrent();
            current = null;
        }
        logDropped(dropped);
    }

    private List<Pending> endCurrent() {
        Lease old = current;
        if (old == null) {
            return List.of();
        }
        old.ended = true;
        // 快照裡有條目內容：作廢就放掉，不讓還留著舊物件的模組透過它拖住
        old.data = null;
        List<Pending> dropped = List.copyOf(old.pending);
        old.pending.clear();
        return dropped;
    }

    private static void logDropped(List<Pending> dropped) {
        if (!dropped.isEmpty()) {
            LOG.log(Level.FINE, "上鎖前沒等到內容，略過 {0} 個等待中的模組", dropped.size());
        }
    }

    /**
     * 給模組（{@code ModuleHost.unlockedEntries()}）：解鎖中而且內容已載入時回傳這次解鎖的查詢。
     *
     * @return 上鎖（含 App 行程剛被系統重啟、還沒解鎖）或內容還在載入時為空；在載入時順便請外殼開始載入
     */
    public Optional<UnlockedEntries> unlockedEntries() {
        Lease lease = current;
        if (lease == null) {
            return Optional.empty();
        }
        if (lease.data == null) {
            loader.run();
            return Optional.empty();
        }
        return Optional.of(lease.view);
    }

    /**
     * 給模組（{@code ModuleHost.whenEntriesLoaded(…)}）：等這次解鎖的內容載入好再通知一次，例如解鎖畫面回來後才要查詢。
     * 已載入時立即排進 {@code executor}。
     *
     * @param executor 執行通知的地方（android 的畫面傳主執行緒）
     * @param callback 通知；之後用 {@link #unlockedEntries()} 取得查詢。丟出的例外只記錄類型
     * @return 會通知時為 {@code true}；已上鎖時為 {@code false}（不會通知）。等待中上鎖也不會通知
     */
    public boolean whenLoaded(Executor executor, Runnable callback) {
        Pending pending = new Pending(Objects.requireNonNull(executor, "executor"),
                Objects.requireNonNull(callback, "callback"));
        boolean ready;
        synchronized (this) {
            Lease lease = current;
            if (lease == null) {
                return false;
            }
            ready = lease.data != null;
            if (!ready) {
                lease.pending.add(pending);
            }
        }
        if (ready) {
            pending.fire();
        } else {
            loader.run();
        }
        return true;
    }

    /** 一次解鎖的租約：外殼發布內容的入口。模組拿不到這個物件，只拿到它的唯讀查詢。 */
    public final class Lease {

        private volatile ScreenData data;
        private volatile boolean ended;
        // 只在 ModuleVaultAccess 的鎖裡讀寫
        private final List<Pending> pending = new ArrayList<>();
        private final UnlockedEntries view = new View(this);

        private Lease() {
        }

        /**
         * 這次解鎖的內容載入或換新了（編輯、存檔、合併後）。租約已作廢時不做事（上鎖後背景存檔完成才送來的快照）。
         * 第一次發布時通知等待中的 {@link #whenLoaded}。
         *
         * @param snapshot 目前的內容；{@code null} 時不做事
         */
        public void publish(ScreenData snapshot) {
            if (snapshot == null) {
                return;
            }
            List<Pending> ready;
            synchronized (ModuleVaultAccess.this) {
                if (ended) {
                    return;
                }
                data = snapshot;
                ready = List.copyOf(pending);
                pending.clear();
            }
            for (Pending p : ready) {
                p.fire();
            }
        }

        /** @return 還沒作廢（這次解鎖還在） */
        public boolean active() {
            return !ended;
        }
    }

    private record Pending(Executor executor, Runnable callback) {

        void fire() {
            try {
                executor.execute(() -> {
                    try {
                        callback.run();
                    } catch (RuntimeException e) {
                        // 模組的錯誤不往外殼的執行緒傳；只記類型（訊息可能含模組處理中的資料）
                        LOG.log(Level.WARNING, "模組等待內容的通知出錯：{0}", e.getClass().getName());
                    }
                });
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "無法排入模組等待內容的通知：{0}", e.getClass().getName());
            }
        }
    }

    /** 模組拿到的唯讀查詢：每次查詢都讀租約目前的快照，作廢後一律回傳空的結果。 */
    private static final class View implements UnlockedEntries {

        private final Lease lease;

        View(Lease lease) {
            this.lease = lease;
        }

        /** @return 租約還在時的快照；作廢或還沒有時為 {@code null} */
        private ScreenData snapshot() {
            ScreenData d = lease.data;
            return lease.ended ? null : d;
        }

        @Override
        public boolean isOpen() {
            return snapshot() != null;
        }

        @Override
        public List<EntryView> all() {
            ScreenData d = snapshot();
            return d == null ? List.of() : d.query().all();
        }

        @Override
        public List<EntryView> search(String keyword) {
            ScreenData d = snapshot();
            return d == null ? List.of() : d.query().search(keyword);
        }

        @Override
        public List<EntryView> autofill(String packageName, String webDomain) {
            ScreenData d = snapshot();
            return d == null ? List.of() : d.query().autofill(packageName, webDomain);
        }

        @Override
        public Optional<EntryView> entry(UUID entryId) {
            ScreenData d = snapshot();
            return d == null ? Optional.empty() : d.entry(entryId);
        }

        @Override
        public String groupPath(UUID groupId) {
            ScreenData d = snapshot();
            // Optional.isEmpty() 是 Java 11，Android API 29 沒有（Animal Sniffer）
            if (d == null || groupId == null || !d.group(groupId).isPresent()) {
                return "";
            }
            return d.query().groupPath(groupId);
        }

        @Override
        public String toString() {
            // 不輸出任何條目內容（CONVENTIONS §2）
            return "UnlockedEntries[open=" + isOpen() + "]";
        }
    }
}
