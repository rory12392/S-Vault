package app.svault.android.shell.entries;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import app.svault.android.shell.start.BackgroundJob;
import app.svault.android.ui.Toasts;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ConflictChoices;
import app.svault.core.shell.PendingEdit;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import app.svault.core.sync.SyncReport;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 解鎖期間所有條目畫面共用的狀態（一次解鎖一個，由 {@code SvaultApplication} 持有，上鎖時丟掉）：
 * 目前的內容快照、存檔或同步中、同步狀態（尚未同步、有衝突），以及要在下一個畫面顯示的提示。
 *
 * <p>寫入一律經 {@link VaultOps}（{@code VaultEditor} → {@code VaultSession.save()}），在 App 唯一的背景執行緒
 * 執行；結果由發起操作的畫面的 {@link BackgroundJob} 接收（重建 Activity 也不會丟），內容快照則推給所有畫面。
 * 同一時間只跑一個寫入或同步（DESIGN §6：存檔中可以繼續瀏覽，但不能再改）。
 *
 * <p>同步（T-14，AC-07-5）：第一次載入後讀一次遠端，之後畫面回到前景（{@code onResume}）時至少間隔 30 秒再讀；
 * 尚未同步時每 60 秒自動重試。狀態只用操作結果更新（{@link VaultOps.Outcome#state}），主執行緒不問
 * {@code VaultSession}（背景同步時它被鎖住）。
 */
public final class EntriesModel {

    /**
     * 換畫面時要在下一個畫面顯示的提示（例如儲存後關掉編輯畫面，「已儲存」顯示在詳細頁）。
     *
     * @param message 文字
     * @param kind    種類
     */
    record Notice(CharSequence message, Toasts.Kind kind) { }

    /**
     * 同步 Banner 要顯示的狀態。
     *
     * @param state     同步狀態
     * @param conflicts 衝突筆數（{@code CONFLICT} 時）
     * @param syncing   正在讀遠端（「正在同步最新資料…」）
     */
    record SyncView(VaultOps.SyncState state, int conflicts, boolean syncing) { }

    private final VaultSession session;
    private final Executor worker;
    private final VaultOps ops;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable retry = this::retryIfPending;
    private final MutableLiveData<ScreenData> data = new MutableLiveData<>();
    private final MutableLiveData<Boolean> saving = new MutableLiveData<>(false);
    private final MutableLiveData<SyncView> sync =
            new MutableLiveData<>(new SyncView(VaultOps.SyncState.SYNCED, 0, false));
    private final MutableLiveData<SyncReport> merged = new MutableLiveData<>();
    private boolean loading;
    private volatile boolean disposed;
    /** 背景執行緒上最後的同步狀態（只在 worker 讀寫）。 */
    private VaultOps.SyncState state = VaultOps.SyncState.SYNCED;
    private int conflicts;
    @Nullable private Notice notice;
    /** 衝突對照畫面的選擇（T-16）：關掉再開、旋轉時沿用；上鎖時加密保存（AC-08-5，T-18），這個物件跟著丟掉。 */
    @Nullable private ConflictChoices choices;
    /** 編輯畫面上還沒儲存的內容（T-18：上鎖時先存）；沒有編輯畫面時 {@code null}。 */
    @Nullable private Supplier<PendingEdit> editSource;
    private final MutableLiveData<Boolean> resume = new MutableLiveData<>(false);

    /**
     * @param session 解鎖狀態
     * @param worker  保管庫操作的背景執行緒（{@code SvaultApplication.worker()}）
     * @param clock   修改時間的來源
     */
    public EntriesModel(VaultSession session, Executor worker, Clock clock) {
        this.session = Objects.requireNonNull(session, "session");
        this.worker = Objects.requireNonNull(worker, "worker");
        this.ops = new VaultOps(session, clock);
    }

    /** @return 目前的內容；還沒載入時值是 {@code null}（畫面顯示「正在載入條目…」） */
    LiveData<ScreenData> data() {
        return data;
    }

    /** @return 有寫入或同步正在進行 */
    LiveData<Boolean> saving() {
        return saving;
    }

    /** @return 同步狀態（Banner） */
    LiveData<SyncView> sync() {
        return sync;
    }

    /** @return 剛合併了另一台裝置的修改（顯示一次「已更新為最新資料（合併 n 筆）」後以 {@link #consumeMerged()} 清掉） */
    LiveData<SyncReport> merged() {
        return merged;
    }

    /** 合併的提示已顯示。 */
    @MainThread
    void consumeMerged() {
        merged.setValue(null);
    }

    /** 第一次需要內容時在背景建立快照（2,000 筆不在主執行緒做），接著讀一次遠端的最新版本（AC-07-5）。 */
    @MainThread
    void ensureLoaded() {
        if (data.getValue() != null || loading) {
            return;
        }
        loading = true;
        saving.setValue(true);
        sync.setValue(new SyncView(VaultOps.SyncState.SYNCED, 0, true));
        worker.execute(() -> {
            try {
                data.postValue(ops.load());
                deliver(ops.refresh(), true);
                // AC-08-5：上次衝突沒解決就上鎖、現在仍有衝突 → 回到對照畫面並帶入當時的選擇
                ops.resumeConflicts().ifPresent(resumed -> main.post(() -> {
                    choices = resumed;
                    resume.setValue(true);
                }));
            } catch (IllegalStateException e) {
                // 載入前已上鎖：畫面會被導回解鎖畫面
            } finally {
                saving.postValue(false);
            }
        });
    }

    /** 畫面回到前景：距離上次讀遠端超過 30 秒才讀（Architecture §5）。 */
    @MainThread
    void refreshIfDue() {
        if (data.getValue() != null && !busy() && ops.refreshDue()) {
            refresh();
        }
    }

    /** 立刻讀一次遠端（衝突在這之間已經解決時，讓 Banner 回到正確的狀態）。 */
    @MainThread
    void syncNow() {
        if (data.getValue() != null && !busy()) {
            refresh();
        }
    }

    /** @return 寫入或同步正在進行 */
    @MainThread
    boolean busy() {
        return Boolean.TRUE.equals(saving.getValue());
    }

    /**
     * 在背景執行一個寫入操作。
     *
     * @param job 發起的畫面的工作（接收結果）
     * @param tag 標記
     * @param op  操作；丟出例外（條目已不存在、已上鎖）時保管庫不變，結果的 {@code error} 是那個例外
     * @return 已開始；已有寫入在進行時為 {@code false}
     */
    @MainThread
    boolean run(BackgroundJob job, String tag, Function<VaultOps, VaultOps.Outcome> op) {
        return run(job, tag, op, true);
    }

    /**
     * 衝突對照：在背景讀衝突（{@code VaultSession} 在背景同步時被鎖住，主執行緒不問）。結果是
     * {@link ConflictView}；這之間衝突已經解決時是 {@code null}。
     *
     * @param job 發起的畫面的工作
     * @param tag 標記
     * @return 已開始；有寫入或同步進行中時為 {@code false}
     */
    @MainThread
    boolean loadConflicts(BackgroundJob job, String tag) {
        return !busy() && job.start(worker, tag, () -> ops.conflicts().orElse(null));
    }

    /**
     * 開啟對照畫面：帶入上一次的選擇（只沿用兩邊的值都沒變的欄位，D-105）。
     *
     * @param view 剛讀到的衝突
     * @return 這次的選擇（畫面直接修改它）
     */
    @MainThread
    ConflictChoices openConflicts(ConflictView view) {
        choices = choices == null ? new ConflictChoices(view)
                : new ConflictChoices(view, choices.view(), choices.toMap());
        return choices;
    }

    /** @return 對照畫面目前的選擇；沒開過時 {@code null}（重建的對照畫面據此還原） */
    @MainThread
    @Nullable
    ConflictChoices conflictChoices() {
        return choices;
    }

    /**
     * 「完成並儲存」：在背景以目前的選擇寫回（存檔流程同 US-07）。不另外顯示「已更新為最新資料」（D-107）；
     * 寫回成功後由畫面呼叫 {@link #conflictsResolved()}。
     *
     * @param job 發起的畫面的工作
     * @param tag 標記
     * @return 已開始；還沒選完、或已有寫入進行中時為 {@code false}
     */
    @MainThread
    boolean resolve(BackgroundJob job, String tag) {
        ConflictChoices c = choices;
        if (c == null || !c.complete()) {
            return false;
        }
        Map<UUID, Map<String, Side>> chosen = c.toMap();
        return run(job, tag, o -> o.resolve(chosen), false);
    }

    /** 衝突已解決並寫回：丟掉記著的選擇。 */
    @MainThread
    void conflictsResolved() {
        choices = null;
    }

    @MainThread
    private boolean run(BackgroundJob job, String tag, Function<VaultOps, VaultOps.Outcome> op, boolean announce) {
        if (busy()) {
            return false;
        }
        saving.setValue(true);
        boolean started = job.start(worker, tag, () -> {
            try {
                VaultOps.Outcome out = op.apply(ops);
                // 依序貼回主執行緒：內容 → 同步狀態 → 存檔中結束 → 發起畫面的結果
                deliver(out, announce);
                return out;
            } finally {
                saving.postValue(false);
            }
        });
        if (!started) {
            saving.setValue(false);
        }
        return started;
    }

    /**
     * 上鎖（{@code SvaultApplication.lockNow()}）：在主執行緒記下編輯畫面上沒儲存的內容與衝突對照的選擇，排到背景執行緒
     * （等進行中的存檔結束）先存檔再上鎖（AC-12-3；有衝突時連同選擇加密保存，AC-08-5）。
     */
    @MainThread
    public void lock() {
        PendingEdit edit = editSource == null ? null : editSource.get();
        Map<UUID, Map<String, Side>> chosen = choices == null ? Map.of() : choices.toMap();
        worker.execute(() -> ops.lock(edit, chosen));
    }

    /**
     * 編輯畫面登記「目前還沒儲存的內容」（上鎖時先存，AC-12-3）；畫面結束時以 {@link #clearEditSource} 取消。
     *
     * @param source 回傳編輯中而且有修改時的內容，否則 {@code null}（主執行緒呼叫）
     */
    @MainThread
    void setEditSource(Supplier<PendingEdit> source) {
        editSource = source;
    }

    /** @param source 之前登記的（不是同一個時不動：新畫面可能已經登記了） */
    @MainThread
    void clearEditSource(Supplier<PendingEdit> source) {
        if (editSource == source) {
            editSource = null;
        }
    }

    /**
     * @return 解鎖後第一次更新發現上次衝突沒解決就上鎖（AC-08-5）：值為 {@code true} 時開對照畫面（選擇已帶入
     *         {@link #conflictChoices()}），開了以後 {@link #consumeResume()}
     */
    LiveData<Boolean> resume() {
        return resume;
    }

    /** 回到對照畫面的通知已處理。 */
    @MainThread
    void consumeResume() {
        resume.setValue(false);
    }

    /**
     * 還原前（T-12，在背景執行緒）：有存檔失敗的修改時先再存一次，讓「還原前的備份」包含它（同上鎖前，AC-12-3）。
     */
    @WorkerThread
    public void saveUnsaved() {
        ops.saveBeforeLock();
    }

    /** 上鎖或還原後丟掉這個狀態：停止自動重試（任何執行緒都可以呼叫）。 */
    public void dispose() {
        disposed = true;
        main.removeCallbacks(retry);
    }

    /**
     * @param message 下一個顯示的條目畫面要顯示的提示
     * @param kind    種類
     */
    @MainThread
    void notice(CharSequence message, Toasts.Kind kind) {
        notice = new Notice(message, kind);
    }

    /** @return 待顯示的提示（取走後清掉）；沒有時 {@code null} */
    @MainThread
    @Nullable
    Notice takeNotice() {
        Notice n = notice;
        notice = null;
        return n;
    }

    @MainThread
    private void refresh() {
        saving.setValue(true);
        sync.setValue(new SyncView(state(), conflictCount(), true));
        worker.execute(() -> {
            try {
                deliver(ops.refresh(), true);
            } catch (IllegalStateException e) {
                // 同步前已上鎖
            } finally {
                saving.postValue(false);
            }
        });
    }

    @WorkerThread
    void deliver(VaultOps.Outcome out, boolean announce) {
        if (out.data() != null) {
            data.postValue(out.data());
        }
        state = out.state(state);
        if (out.sync() != null && out.sync().hasConflicts()) {
            conflicts = out.sync().conflicts();
        }
        sync.postValue(new SyncView(state, conflicts, false));
        if (announce && out.merged()) {
            merged.postValue(out.sync());
        }
        main.post(this::scheduleRetry);
    }

    @MainThread
    private void scheduleRetry() {
        main.removeCallbacks(retry);
        if (!disposed && state() == VaultOps.SyncState.PENDING) {
            main.postDelayed(retry, VaultOps.RETRY_INTERVAL.toMillis());
        }
    }

    @MainThread
    private void retryIfPending() {
        if (disposed || state() != VaultOps.SyncState.PENDING) {
            return;
        }
        if (busy()) {
            scheduleRetry();
        } else {
            refresh();
        }
    }

    // 主執行緒讀畫面上目前的狀態（LiveData 的值）
    private VaultOps.SyncState state() {
        SyncView v = sync.getValue();
        return v == null ? VaultOps.SyncState.SYNCED : v.state();
    }

    private int conflictCount() {
        SyncView v = sync.getValue();
        return v == null ? 0 : v.conflicts();
    }
}
