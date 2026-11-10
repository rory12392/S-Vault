package app.svault.android.shell.entries;

import android.os.Bundle;
import android.view.View;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.SecureActivity;
import app.svault.android.shell.start.BackgroundJob;
import app.svault.android.ui.Banner;
import app.svault.android.ui.Toasts;
import app.svault.core.shell.VaultOps;
import app.svault.core.sync.ConflictView;
import app.svault.core.vault.EntryView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.util.function.Function;

/**
 * 條目畫面（首頁群組清單、條目清單、詳細、編輯）的共同部分：{@code FLAG_SECURE} 與上鎖導回（{@link SecureActivity}）、
 * Top app bar 的上鎖按鈕（AC-12-5）、存檔中的進度條、存檔失敗的「尚未同步」Banner＋「再試一次」、換畫面帶過來的提示、
 * 寫入操作的結果。
 *
 * <p>子類別在 {@code onCreate} 先呼叫 {@link #model()}：已上鎖（例如 App 被系統重建）時是 {@code null}，
 * 不要建立畫面內容，{@code onStart} 會導回解鎖畫面。
 */
public abstract class EntriesActivity extends SecureActivity {

    /** 「再試一次」的工作標記。 */
    static final String JOB_RETRY = "retry";
    /** 衝突 Banner「處理」：讀衝突的工作標記。 */
    static final String JOB_CONFLICTS = "conflicts";
    /** 衝突對照「完成並儲存」：寫回的工作標記。 */
    static final String JOB_RESOLVE = "resolve";

    private SvaultApplication app;
    @Nullable private EntriesModel model;
    private BackgroundJob job;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        app = (SvaultApplication) getApplication();
        model = app.entries();
        job = new ViewModelProvider(this).get("ops", BackgroundJob.class);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (model == null) {
            return;
        }
        EntriesModel.Notice n = model.takeNotice();
        if (n != null) {
            Toasts.show(snackbarAnchor(), n.message(), n.kind());
        }
        if (syncOnResume()) {
            // AC-07-5：回到前景時讀遠端的最新版本（至少間隔 30 秒；第一次載入時已讀過）
            model.refreshIfDue();
        }
    }

    /** @return 回到這個畫面時要不要讀遠端（編輯畫面不讀，避免改到一半內容被換掉） */
    protected boolean syncOnResume() {
        return true;
    }

    /** @return 這次解鎖的條目狀態；已上鎖時 {@code null} */
    @Nullable
    protected final EntriesModel model() {
        return model;
    }

    /** @return App（背景執行緒、剪貼簿、功能模組） */
    protected final SvaultApplication app() {
        return app;
    }

    /** @return Snackbar 的錨點（畫面根部的 CoordinatorLayout 內任一 View） */
    protected abstract View snackbarAnchor();

    /**
     * Top app bar：上鎖按鈕；{@code up} 時左上角是返回。
     *
     * @param toolbar Top app bar（選單用 {@code entries_menu}）
     * @param up      顯示返回
     */
    protected final void setUpToolbar(MaterialToolbar toolbar, boolean up) {
        if (up) {
            toolbar.setNavigationIcon(R.drawable.sv_ic_arrow_back);
            toolbar.setNavigationContentDescription(R.string.sv_navigate_up);
            toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        }
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.entries_lock && model != null) {
                app.lockNow();
                return true;
            }
            return onToolbarItem(item.getItemId());
        });
    }

    /**
     * @param itemId Top app bar 選單中上鎖以外的項目
     * @return 已處理
     */
    protected boolean onToolbarItem(int itemId) {
        return false;
    }

    /**
     * 存檔中（進度條）與存檔失敗（Banner）的顯示，並開始接收寫入操作的結果。
     *
     * @param banner   「尚未同步」Banner；這個畫面不顯示時 {@code null}
     * @param progress 存檔中的進度條；這個畫面不顯示時 {@code null}
     */
    protected final void bindStatus(@Nullable Banner banner, @Nullable LinearProgressIndicator progress) {
        if (model == null) {
            return;
        }
        model.sync().observe(this, view -> {
            if (banner != null && view != null) {
                showSync(banner, view);
            }
        });
        if (banner != null) {
            // AC-08-5：解鎖後回到上次沒解決的衝突（選擇已在 model 裡）
            model.resume().observe(this, now -> {
                if (Boolean.TRUE.equals(now) && model.conflictChoices() != null) {
                    model.consumeResume();
                    showConflictDialog();
                }
            });
        }
        model.merged().observe(this, report -> {
            if (report != null) {
                model.consumeMerged();
                Toasts.show(snackbarAnchor(), SyncTexts.merged(this, report), Toasts.Kind.INFO)
                        .setTextMaxLines(SyncTexts.MAX_LINES);
            }
        });
        model.saving().observe(this, saving -> {
            boolean on = Boolean.TRUE.equals(saving);
            if (progress != null) {
                progress.setVisibility(on ? View.VISIBLE : View.GONE);
            }
            onSavingChanged(on);
        });
        job.result().observe(this, this::deliver);
    }

    // 同步 Banner：同步中 info、尚未同步 warning＋「再試一次」、有衝突 error＋「處理」（T-16）
    private void showSync(Banner banner, EntriesModel.SyncView view) {
        if (view.syncing()) {
            banner.show(Banner.Kind.INFO, getString(R.string.entries_syncing), null, null);
        } else if (view.state() == VaultOps.SyncState.PENDING) {
            banner.show(Banner.Kind.WARNING, getString(R.string.entries_unsaved), getString(R.string.entries_retry),
                    v -> runOp(JOB_RETRY, VaultOps::retrySave));
        } else if (view.state() == VaultOps.SyncState.CONFLICT) {
            banner.show(Banner.Kind.ERROR, getString(R.string.entries_conflicts, view.conflicts()),
                    getString(R.string.entries_resolve), v -> openConflicts());
        } else {
            banner.setVisibility(View.GONE);
            return;
        }
        banner.setVisibility(View.VISIBLE);
    }

    // ---------- 衝突（T-16，US-08） ----------

    /** Banner「處理」：在背景讀衝突，讀到後開對照畫面（{@link ConflictDialog}）。 */
    private void openConflicts() {
        if (model != null && getSupportFragmentManager().findFragmentByTag(ConflictDialog.TAG) == null) {
            model.loadConflicts(job, JOB_CONFLICTS);
        }
    }

    private void showConflicts(@Nullable ConflictView view) {
        if (model == null) {
            return;
        }
        if (view == null || view.items().isEmpty()) {
            // 這之間已經解決（例如自動重試時另一台的修改讓衝突消失）：重新讀一次狀態
            model.syncNow();
            return;
        }
        model.openConflicts(view);
        showConflictDialog();
    }

    private void showConflictDialog() {
        if (getSupportFragmentManager().findFragmentByTag(ConflictDialog.TAG) == null
                && !getSupportFragmentManager().isStateSaved()) {
            new ConflictDialog().show(getSupportFragmentManager(), ConflictDialog.TAG);
        }
    }

    /**
     * 對照畫面的「完成並儲存」（全部選完時）：在背景寫回。
     *
     * @return 已開始（對照畫面可以關閉）；已有寫入進行中時 {@code false}
     */
    boolean resolveConflicts() {
        return model != null && model.resolve(job, JOB_RESOLVE);
    }

    /**
     * @param saving 寫入進行中時為 {@code true}（停用新增、編輯、刪除；仍可瀏覽與搜尋）
     */
    protected void onSavingChanged(boolean saving) {
    }

    /**
     * @param tag 標記
     * @param op  寫入操作
     * @return 已開始；已有寫入在進行時 {@code false}（按鈕已停用，通常不會發生）
     */
    protected final boolean runOp(String tag, Function<VaultOps, VaultOps.Outcome> op) {
        return model != null && model.run(job, tag, op);
    }

    private void deliver(@Nullable BackgroundJob.Outcome outcome) {
        if (outcome == null) {
            return;
        }
        job.consume();
        if (JOB_CONFLICTS.equals(outcome.tag()) && outcome.error() == null) {
            showConflicts((ConflictView) outcome.value());
            return;
        }
        if (outcome.error() != null) {
            // 條目已不存在、已上鎖等：保管庫沒有改變（DESIGN §6：不顯示例外訊息）
            Toasts.show(snackbarAnchor(), getString(R.string.entries_toast_failed), Toasts.Kind.ERROR);
            onOpFailed(outcome.tag());
            return;
        }
        VaultOps.Outcome out = (VaultOps.Outcome) outcome.value();
        if (JOB_RETRY.equals(outcome.tag())) {
            if (out.saved()) {
                Toasts.show(snackbarAnchor(), getString(R.string.entries_toast_saved), Toasts.Kind.SUCCESS);
            }
            return;
        }
        if (JOB_RESOLVE.equals(outcome.tag())) {
            // 寫回時離線：Banner 換成「尚未同步」、選擇留著；另一台又改了：重新合併，Banner 再出現（D-108）
            if (out.saved() && model != null) {
                model.conflictsResolved();
                Toasts.show(snackbarAnchor(), getString(R.string.entries_toast_resolved), Toasts.Kind.SUCCESS);
            }
            return;
        }
        onOpDone(outcome.tag(), out);
    }

    /**
     * 寫入操作完成（存檔失敗也算完成：修改在記憶體，Banner 會出現）。
     *
     * @param tag 標記
     * @param out 結果
     */
    protected void onOpDone(String tag, VaultOps.Outcome out) {
    }

    /**
     * 寫入操作丟出例外，保管庫沒有改變（已顯示 error Toast）。
     *
     * @param tag 標記
     */
    protected void onOpFailed(String tag) {
    }

    /** @return 這個畫面發起的寫入還沒交回結果（重建 Activity 後依此還原 loading 狀態） */
    protected final boolean opRunning() {
        return job.isRunning();
    }

    /**
     * 複製密碼（AC-05-1）：依設定的秒數後清除，提示的秒數用實際排定的那一個（T-19）。
     *
     * @param entry 條目
     */
    protected final void copyPassword(EntryView entry) {
        long seconds = app.clipboard().copySecret(entry.password()).getSeconds();
        Toasts.show(snackbarAnchor(), getString(R.string.entries_toast_copied_password, seconds), Toasts.Kind.INFO);
    }
}
