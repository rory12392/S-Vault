package app.svault.android.shell.start;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.platform.OpenFailure;
import app.svault.android.platform.SafVaultLocation;
import app.svault.android.ui.Banner;
import app.svault.android.ui.EmptyState;
import app.svault.android.ui.SystemBars;
import app.svault.android.ui.Toasts;
import app.svault.core.shell.Texts;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.VaultIoException;
import com.google.android.material.button.MaterialButton;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 歡迎／最近開啟（AC-06-2、AC-06-3）。App 的進入點。
 *
 * <p>只有一筆最近開啟的檔案時，啟動直接進解鎖畫面；從解鎖畫面按「開啟其他檔案」回來時不再自動前進。
 * 不顯示保管庫內容，所以不設 {@code FLAG_SECURE}。
 */
public class WelcomeActivity extends AppCompatActivity {

    /** {@code true}：不因為只有一筆最近開啟而自動進解鎖畫面。 */
    static final String EXTRA_STAY = "app.svault.android.extra.STAY";

    private static final String JOB_LOAD = "load";
    private static final String JOB_PICK = "pick";
    private static final String JOB_FORGET = "forget";
    /** 所有時間以裝置時區、yyyy/MM/dd HH:mm 顯示。 */
    private static final DateTimeFormatter TIME = Texts.MINUTES;

    private SvaultApplication app;
    private StartActions actions;
    private BackgroundJob job;
    private BackgroundJob pickJob;
    private Banner banner;
    private View recentBlock;
    private EmptyState empty;
    private final RecentAdapter adapter = new RecentAdapter();
    private boolean autoForward;
    private ActivityResultLauncher<String[]> openDocument;

    /**
     * @param context 目前的畫面
     * @return 回到歡迎畫面（不自動前進）、清掉其他畫面的 Intent
     */
    static Intent stayIntent(Context context) {
        return new Intent(context, WelcomeActivity.class).putExtra(EXTRA_STAY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_welcome);
        SystemBars.applyInsets(findViewById(R.id.welcome_root));
        app = (SvaultApplication) getApplication();
        actions = app.startActions();
        ViewModelProvider models = new ViewModelProvider(this);
        job = models.get("list", BackgroundJob.class);
        // 選檔另用一個：讀清單還沒回來時選了檔案，不能被「一次只跑一個」擋掉
        pickJob = models.get("pick", BackgroundJob.class);

        banner = findViewById(R.id.welcome_banner);
        recentBlock = findViewById(R.id.welcome_recent_block);
        empty = findViewById(R.id.welcome_empty);
        RecyclerView list = findViewById(R.id.welcome_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        openDocument = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);
        MaterialButton open = findViewById(R.id.welcome_open);
        open.setOnClickListener(v -> pickFile());
        findViewById(R.id.welcome_create).setOnClickListener(
                v -> startActivity(new Intent(this, CreateVaultActivity.class)));

        if (actions == null) {
            // 本機資料夾建立失敗（幾乎只有儲存空間滿了）：說明原因，不閃退
            banner.show(Banner.Kind.ERROR, getString(R.string.start_storage_failed), null, null);
            banner.setVisibility(View.VISIBLE);
            open.setEnabled(false);
            findViewById(R.id.welcome_create).setEnabled(false);
            return;
        }
        autoForward = savedInstanceState == null && !getIntent().getBooleanExtra(EXTRA_STAY, false);
        job.result().observe(this, o -> onJobDone(job, o));
        pickJob.result().observe(this, o -> onJobDone(pickJob, o));
        if (!job.isRunning()) {
            load();
        }
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        // 從其他畫面回來（例如建立後又上鎖、在解鎖畫面移除了權限）：清單可能變了
        if (actions != null && !job.isRunning()) {
            load();
        }
    }

    private void load() {
        job.start(app.worker(), JOB_LOAD, actions::recent);
    }

    private void pickFile() {
        // Drive 給 .kdbx 的 MIME 類型不一定（application/octet-stream、application/x-keepass2…），不篩選
        openDocument.launch(new String[] {"*/*"});
    }

    private void onPicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        String picked = uri.toString();
        pickJob.start(app.worker(), JOB_PICK, () -> actions.pick(picked));
    }

    @SuppressWarnings("unchecked")
    private void onJobDone(BackgroundJob source, @Nullable BackgroundJob.Outcome outcome) {
        if (outcome == null) {
            return;
        }
        source.consume();
        switch (outcome.tag()) {
            case JOB_LOAD:
                showRecent(outcome.error() == null ? (List<RecentVault>) outcome.value() : List.of());
                break;
            case JOB_PICK:
                if (outcome.error() instanceof VaultIoException) {
                    showFailure(OpenFailure.of((VaultIoException) outcome.error(), false));
                } else if (outcome.error() == null) {
                    banner.setVisibility(View.GONE);
                    SafVaultLocation picked = (SafVaultLocation) outcome.value();
                    startActivity(UnlockActivity.intent(this, picked.persistentId(), picked.displayName()));
                }
                break;
            case JOB_FORGET:
                if (outcome.error() != null) {
                    Toasts.show(banner, getString(R.string.welcome_remove_failed), Toasts.Kind.ERROR);
                } else {
                    Toasts.show(banner, getString(R.string.welcome_removed, outcome.value()), Toasts.Kind.INFO);
                }
                load();
                break;
            default:
                break;
        }
    }

    private void showRecent(List<RecentVault> recent) {
        if (autoForward && recent.size() == 1) {
            // AC-06-3：只有一筆時直接進解鎖畫面；讀不到的原因由解鎖畫面顯示
            autoForward = false;
            RecentVault only = recent.get(0);
            startActivity(UnlockActivity.intent(this, only.location(), only.displayName()));
            finish();
            return;
        }
        autoForward = false;
        adapter.submit(recent);
        boolean none = recent.isEmpty();
        recentBlock.setVisibility(none ? View.GONE : View.VISIBLE);
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) {
            empty.show(R.drawable.sv_ic_folder, getString(R.string.welcome_empty_title),
                    getString(R.string.welcome_empty_body), null, null);
        }
    }

    private void showFailure(OpenFailure f) {
        CharSequence message = f.messageArg() == null ? getString(f.messageRes())
                : getString(f.messageRes(), f.messageArg());
        boolean reselect = f.action() != OpenFailure.Action.NONE;
        banner.show(Banner.Kind.ERROR, message, reselect ? getString(R.string.sv_open_reselect) : null,
                reselect ? v -> pickFile() : null);
        banner.setVisibility(View.VISIBLE);
    }

    private void forget(RecentVault item) {
        job.start(app.worker(), JOB_FORGET, () -> {
            actions.forget(item);
            return item.displayName();
        });
    }

    private void showMenu(View anchor, RecentVault item) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.welcome_recent_remove);
        menu.setOnMenuItemClickListener(mi -> {
            forget(item);
            return true;
        });
        menu.show();
    }

    private final class RecentAdapter extends RecyclerView.Adapter<RecentHolder> {

        private final List<RecentVault> items = new ArrayList<>();

        void submit(List<RecentVault> recent) {
            items.clear();
            items.addAll(recent);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public RecentHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new RecentHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.welcome_recent_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull RecentHolder holder, int position) {
            RecentVault item = items.get(position);
            String opened = TIME.format(item.lastOpened().atZone(ZoneId.systemDefault()));
            holder.name.setText(item.displayName());
            holder.opened.setText(getString(R.string.welcome_recent_opened, opened));
            holder.itemView.setContentDescription(
                    getString(R.string.welcome_recent_item_accessible, item.displayName(), opened));
            holder.itemView.setOnClickListener(
                    v -> startActivity(UnlockActivity.intent(WelcomeActivity.this, item.location(),
                            item.displayName())));
            holder.more.setContentDescription(getString(R.string.welcome_recent_more, item.displayName()));
            holder.more.setOnClickListener(v -> showMenu(v, item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private static final class RecentHolder extends RecyclerView.ViewHolder {

        final TextView name;
        final TextView opened;
        final MaterialButton more;

        RecentHolder(View row) {
            super(row);
            name = row.findViewById(R.id.recent_name);
            opened = row.findViewById(R.id.recent_opened);
            more = row.findViewById(R.id.recent_more);
        }
    }
}
