package app.svault.android.shell.start;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.entries.EntriesModel;
import app.svault.android.shell.entries.HomeActivity;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.EmptyState;
import app.svault.android.ui.ProgressOverlay;
import app.svault.android.ui.SystemBars;
import app.svault.android.ui.Toasts;
import app.svault.core.shell.Texts;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.CorruptBackupException;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.radiobutton.MaterialRadioButton;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 備份還原（AC-09-2、AC-09-5）：備份時間清單（新到舊，最多 10 列，單選）→「還原這個版本」（danger）→
 * 確認「還原到 … 的版本？」。沒有備份時 EmptyState（無行動）。兩種用法：
 * <ul>
 *   <li>解鎖中（首頁溢位選單）：確認後在這裡還原（ProgressOverlay「正在還原…」），成功後丟掉條目狀態、
 *       重新開首頁並顯示「已還原」；失敗時 error Toast，留在清單上。</li>
 *   <li>上鎖中（解鎖畫面的「從備份還原」，檔案損壞時）：確認後把選定的備份交回解鎖畫面，在那裡輸入主密碼後
 *       「還原並解鎖」（主密碼先解開備份確認可用才寫，D-76）。</li>
 * </ul>
 * 只列時間、不顯示保管庫內容，所以不設 {@code FLAG_SECURE}（同解鎖畫面）。
 */
public class BackupActivity extends AppCompatActivity {

    private static final String TAG = "SVault";
    private static final String EXTRA_URI = "app.svault.android.extra.URI";
    static final String EXTRA_BACKUP_FILE = "app.svault.android.extra.BACKUP_FILE";
    static final String EXTRA_BACKUP_TIME = "app.svault.android.extra.BACKUP_TIME";
    private static final String STATE_SELECTED = "selected";
    private static final String JOB_LIST = "list";
    private static final String JOB_RESTORE = "restore";
    /** 清單與確認的時間格式；備份檔名到秒，同一分鐘內常有好幾份，所以顯示到秒（同 desktop）。 */
    static final DateTimeFormatter TIME = Texts.SECONDS;

    private SvaultApplication app;
    private StartActions actions;
    private BackgroundJob listJob;
    private BackgroundJob restoreJob;
    @Nullable private String uri;
    private View root;
    private TextView hint;
    private RecyclerView list;
    private View emptyScroll;
    private EmptyState empty;
    private MaterialButton restore;
    private ProgressOverlay overlay;
    private final List<BackupStore.Backup> backups = new ArrayList<>();
    private int selected = -1;
    private final RowAdapter adapter = new RowAdapter();

    /**
     * @param context 首頁
     * @return 解鎖中還原開啟中的保管庫
     */
    public static Intent unlocked(Context context) {
        return new Intent(context, BackupActivity.class);
    }

    /**
     * @param context 解鎖畫面
     * @param uri     損壞的保管庫的 {@code persistentId}
     * @return 選擇要還原的備份，結果交回解鎖畫面（{@link #chosen}）
     */
    static Intent forLocked(Context context, String uri) {
        return new Intent(context, BackupActivity.class).putExtra(EXTRA_URI, uri);
    }

    /**
     * @param data 上鎖中模式的結果
     * @return 選定的備份；沒有時 {@code null}
     */
    @Nullable
    static BackupStore.Backup chosen(@Nullable Intent data) {
        if (data == null || data.getStringExtra(EXTRA_BACKUP_FILE) == null) {
            return null;
        }
        return new BackupStore.Backup(Paths.get(data.getStringExtra(EXTRA_BACKUP_FILE)),
                LocalDateTime.parse(data.getStringExtra(EXTRA_BACKUP_TIME)));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_backup);
        root = findViewById(R.id.backup_root);
        SystemBars.applyInsets(root);
        app = (SvaultApplication) getApplication();
        actions = app.startActions();
        uri = getIntent().getStringExtra(EXTRA_URI);
        MaterialToolbar toolbar = findViewById(R.id.backup_toolbar);
        toolbar.setNavigationIcon(R.drawable.sv_ic_arrow_back);
        toolbar.setNavigationContentDescription(R.string.sv_navigate_up);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        hint = findViewById(R.id.backup_hint);
        list = findViewById(R.id.backup_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);
        emptyScroll = findViewById(R.id.backup_empty_scroll);
        empty = findViewById(R.id.backup_empty);
        restore = findViewById(R.id.backup_restore);
        overlay = findViewById(R.id.backup_overlay);
        restore.setOnClickListener(v -> confirm());
        if (savedInstanceState != null) {
            selected = savedInstanceState.getInt(STATE_SELECTED, -1);
        }
        // 還原中不能離開（和編輯畫面的儲存中一樣）
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!restoreJob.isRunning()) {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        ViewModelProvider models = new ViewModelProvider(this);
        listJob = models.get("list", BackgroundJob.class);
        restoreJob = models.get("restore", BackgroundJob.class);
        listJob.result().observe(this, this::onDone);
        restoreJob.result().observe(this, this::onDone);
        if (actions == null) {
            finish();
            return;
        }
        if (restoreJob.isRunning()) {
            overlay.show(getString(R.string.restore_progress));
        }
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 解鎖中模式：上鎖後（例如之後的自動上鎖）不留在這裡，回到解鎖畫面
        if (uri == null && actions != null && !actions.session().isUnlocked() && !restoreJob.isRunning()) {
            startActivity(UnlockActivity.lockedIntent(this));
            finish();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED, selected);
    }

    private void load() {
        String where = uri;
        listJob.start(app.worker(), JOB_LIST, () -> where == null ? actions.backups() : actions.backupsOf(where));
    }

    private void onDone(@Nullable BackgroundJob.Outcome outcome) {
        if (outcome == null) {
            return;
        }
        if (JOB_LIST.equals(outcome.tag())) {
            listJob.consume();
            onListed(outcome);
        } else {
            restoreJob.consume();
            onRestored(outcome);
        }
    }

    @SuppressWarnings("unchecked")
    private void onListed(BackgroundJob.Outcome outcome) {
        if (outcome.error() != null) {
            Log.w(TAG, "無法列出備份：" + outcome.error().getClass().getName());
            Toasts.show(root, getString(R.string.restore_error_list), Toasts.Kind.ERROR);
            return;
        }
        backups.clear();
        backups.addAll((List<BackupStore.Backup>) outcome.value());
        boolean none = backups.isEmpty();
        if (none) {
            empty.show(R.drawable.sv_ic_lock, getString(R.string.restore_empty_title),
                    getString(R.string.restore_empty_body), null, null);
        }
        if (selected >= backups.size()) {
            selected = -1;
        }
        emptyScroll.setVisibility(none ? View.VISIBLE : View.GONE);
        list.setVisibility(none ? View.GONE : View.VISIBLE);
        hint.setVisibility(none ? View.GONE : View.VISIBLE);
        restore.setVisibility(none ? View.GONE : View.VISIBLE);
        restore.setEnabled(selected >= 0);
        adapter.notifyDataSetChanged();
    }

    private void confirm() {
        if (selected < 0 || selected >= backups.size() || restoreJob.isRunning()) {
            return;
        }
        BackupStore.Backup chosen = backups.get(selected);
        ConfirmDialogs.show(this, getString(R.string.restore_confirm_title, chosen.time().format(TIME)),
                getString(R.string.restore_confirm_body), getString(R.string.restore_confirm_action), true,
                (d, which) -> onConfirmed(chosen));
    }

    private void onConfirmed(BackupStore.Backup chosen) {
        if (uri != null) {
            // 上鎖中：交回解鎖畫面，在那裡用主密碼確認並還原
            setResult(RESULT_OK, new Intent()
                    .putExtra(EXTRA_BACKUP_FILE, chosen.file().toString())
                    .putExtra(EXTRA_BACKUP_TIME, chosen.time().toString()));
            finish();
            return;
        }
        EntriesModel entries = app.entries();
        boolean started = restoreJob.start(app.worker(), JOB_RESTORE, () -> {
            if (entries != null) {
                entries.saveUnsaved();
            }
            actions.restore(chosen);
            return null;
        });
        if (started) {
            overlay.show(getString(R.string.restore_progress));
            restore.setEnabled(false);
        }
    }

    private void onRestored(BackgroundJob.Outcome outcome) {
        overlay.hide();
        restore.setEnabled(selected >= 0);
        if (outcome.error() != null) {
            Log.w(TAG, "還原失敗：" + outcome.error().getClass().getName());
            int message = outcome.error() instanceof CorruptBackupException ? R.string.restore_error_backup
                    : R.string.restore_error_failed;
            Toasts.show(root, getString(message), Toasts.Kind.ERROR);
            return;
        }
        // 開啟中的保管庫已換成還原的版本：條目畫面全部以新的內容重新建立
        app.resetEntries();
        startActivity(new Intent(this, HomeActivity.class)
                .putExtra(HomeActivity.EXTRA_RESTORED, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void select(int position) {
        int old = selected;
        selected = position;
        if (old >= 0) {
            adapter.notifyItemChanged(old);
        }
        adapter.notifyItemChanged(position);
        restore.setEnabled(true);
    }

    private final class RowAdapter extends RecyclerView.Adapter<RowHolder> {

        @NonNull
        @Override
        public RowHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RowHolder((MaterialRadioButton) LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.backup_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RowHolder holder, int position) {
            String time = backups.get(position).time().format(TIME);
            holder.row.setText(position == 0 ? getString(R.string.restore_item_latest, time) : time);
            holder.row.setChecked(position == selected);
            holder.row.setOnClickListener(v -> select(holder.getBindingAdapterPosition()));
        }

        @Override
        public int getItemCount() {
            return backups.size();
        }
    }

    private static final class RowHolder extends RecyclerView.ViewHolder {

        final MaterialRadioButton row;

        RowHolder(MaterialRadioButton row) {
            super(row);
            this.row = row;
        }
    }
}
