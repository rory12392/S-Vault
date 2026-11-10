package app.svault.android.shell.start;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.platform.OpenFailure;
import app.svault.android.platform.SafVaultLocation;
import app.svault.android.ui.Banner;
import app.svault.android.ui.Buttons;
import app.svault.android.ui.ProgressOverlay;
import app.svault.android.ui.SnackbarRoom;
import app.svault.android.ui.SystemBars;
import app.svault.android.ui.Toasts;
import app.svault.core.shell.Secrets;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.VaultException;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.List;

/**
 * 解鎖（AC-01-3、AC-01-7、AC-06-4；含 BIO 的 {@link UnlockProvider} 擴充點）。
 *
 * <p>開啟時先在背景確認檔案與權限還在，讀不到時直接顯示原因，不必先輸入主密碼。上鎖後也回到這裡
 * （{@link #lockedIntent}：沒有指定檔案時用最近開啟的第一筆）；模組的頁面遇到上鎖時是回程（{@link #returnIntent}，T-36），
 * 解鎖後回到那一頁，離開去哪裡見 {@link UnlockExits}。不顯示保管庫內容，所以不設 {@code FLAG_SECURE}；
 * 主密碼欄可以切換成明文，所以不讓系統把畫面放進「最近使用的 App」縮圖（API 33 起）。
 */
public class UnlockActivity extends AppCompatActivity {

    private static final String TAG = "SVault";
    static final String EXTRA_URI = "app.svault.android.extra.URI";
    static final String EXTRA_NAME = "app.svault.android.extra.NAME";
    static final String EXTRA_LOCKED = "app.svault.android.extra.LOCKED";
    static final String EXTRA_RETURN = "app.svault.android.extra.UNLOCK_RETURN";

    private static final String JOB_PREPARE = "prepare";
    private static final String JOB_PICK = "pick";
    private static final String JOB_UNLOCK = "unlock";
    /** 超過 5 秒改顯示 ProgressOverlay。 */
    private static final long SLOW_MS = 5000;

    /** 背景準備的結果：讀不到的原因（沒問題時 {@code null}）與保管庫 ID（替代解鎖方式要用）。 */
    private record Prepared(SafVaultLocation location, OpenFailure problem, String vaultId) { }

    private SvaultApplication app;
    private StartActions actions;
    private BackgroundJob prepareJob;
    private BackgroundJob unlockJob;
    private SafVaultLocation location;
    private TextView name;
    private TextView file;
    private Banner banner;
    private TextInputLayout passwordLayout;
    private TextInputEditText password;
    private MaterialButton submit;
    private MaterialButton other;
    private TextView status;
    private ProgressOverlay overlay;
    private UnlockProviderButtons providers;
    private UnlockBanner banners;
    private UnlockExits exits;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable slow = () -> overlay.show(getString(R.string.unlock_slow));
    private ActivityResultLauncher<String[]> openDocument;

    /**
     * @param context     目前的畫面
     * @param uri         保管庫的 Uri（{@code persistentId}）
     * @param displayName 顯示名稱
     * @return 解鎖這個保管庫的 Intent
     */
    public static Intent intent(Context context, String uri, String displayName) {
        return new Intent(context, UnlockActivity.class).putExtra(EXTRA_URI, uri).putExtra(EXTRA_NAME, displayName);
    }

    /**
     * 上鎖後回到解鎖畫面（{@code SecureActivity}）：最近開啟的第一筆就是剛才開著的保管庫，顯示「已上鎖」。
     *
     * @param context 目前的畫面
     * @return Intent
     */
    public static Intent lockedIntent(Context context) {
        return new Intent(context, UnlockActivity.class).putExtra(EXTRA_LOCKED, true);
    }

    /**
     * 回程（T-36）：模組自己開的頁面遇到上鎖時，在同一個工作裡用 Activity Result API 開這個畫面（同 {@link #lockedIntent}，
     * 解鎖最近開啟的第一筆）。解鎖成功回傳 {@code RESULT_OK}；返回、「開啟其他檔案」、沒有可解鎖的保管庫時
     * {@code RESULT_CANCELED}。不清掉工作、不開首頁或歡迎畫面（{@link UnlockExits}）。
     *
     * @param context 目前的畫面
     * @return Intent（不要加 {@code FLAG_ACTIVITY_NEW_TASK}：結果要回到開它的畫面）
     */
    public static Intent returnIntent(Context context) {
        return lockedIntent(context).putExtra(EXTRA_RETURN, true);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false);
        }
        setContentView(R.layout.activity_unlock);
        View root = findViewById(R.id.unlock_root);
        SystemBars.applyInsets(root);
        app = (SvaultApplication) getApplication();
        actions = app.startActions();
        exits = new UnlockExits(this, getIntent().getBooleanExtra(EXTRA_RETURN, false));
        if (actions == null) {
            exits.leave();
            return;
        }
        ViewModelProvider models = new ViewModelProvider(this);
        prepareJob = models.get("prepare", BackgroundJob.class);
        unlockJob = models.get("unlock", BackgroundJob.class);

        name = findViewById(R.id.unlock_name);
        file = findViewById(R.id.unlock_file);
        banner = findViewById(R.id.unlock_banner);
        passwordLayout = findViewById(R.id.unlock_password_layout);
        password = findViewById(R.id.unlock_password);
        submit = findViewById(R.id.unlock_submit);
        other = findViewById(R.id.unlock_other);
        status = findViewById(R.id.unlock_status);
        overlay = findViewById(R.id.unlock_overlay);
        providers = new UnlockProviderButtons(this, findViewById(R.id.unlock_providers), app.modules());

        openDocument = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onPicked);
        banners = new UnlockBanner(this, banner, submit,
                () -> location == null ? null : location.persistentId(), password::requestFocus, this::pickFile);
        banners.restoreState(savedInstanceState);
        submit.setOnClickListener(v -> unlockWithPassword());
        password.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                unlockWithPassword();
                return true;
            }
            return false;
        });
        password.addTextChangedListener(new ClearErrorOnEdit(passwordLayout));
        other.setOnClickListener(v -> exits.leave());

        prepareJob.result().observe(this, o -> onDone(prepareJob, o));
        unlockJob.result().observe(this, o -> onDone(unlockJob, o));

        String uri = getIntent().getStringExtra(EXTRA_URI);
        if (uri != null) {
            location = SafVaultLocation.reopen(app.documents(), uri, getIntent().getStringExtra(EXTRA_NAME));
            showName();
        }
        if (savedInstanceState == null && getIntent().getBooleanExtra(EXTRA_LOCKED, false)) {
            // 最大字體加上鍵盤時不要蓋住「解鎖」與「開啟其他檔案」（T-109）
            SnackbarRoom.keepClear(Toasts.show(root, getString(R.string.unlock_locked), Toasts.Kind.INFO),
                    findViewById(R.id.unlock_scroll), other);
        }
        if (unlockJob.isRunning()) {
            setBusy(true);
        } else if (!prepareJob.isRunning()) {
            prepare(uri);
        }
        password.requestFocus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 主密碼欄自動聚焦並叫出鍵盤
        password.post(() -> {
            if (password.isEnabled() && password.hasFocus()) {
                WindowCompat.getInsetsController(getWindow(), password).show(WindowInsetsCompat.Type.ime());
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        banners.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(slow);
        super.onDestroy();
    }

    private void prepare(@Nullable String uri) {
        prepareJob.start(app.worker(), JOB_PREPARE, () -> {
            SafVaultLocation where = location;
            if (where == null) {
                List<RecentVault> recent = actions.recent();
                if (recent.isEmpty()) {
                    return new Prepared(null, null, null);
                }
                where = actions.location(recent.get(0));
            }
            String key = uri == null ? where.persistentId() : uri;
            String vaultId = actions.recent().stream().filter(r -> r.location().equals(key)).findFirst()
                    .map(r -> r.vaultId() == null ? null : r.vaultId().toString()).orElse(null);
            return new Prepared(where, actions.check(where), vaultId);
        });
    }

    private void showName() {
        name.setText(StartActions.title(location.displayName()));
        file.setText(location.displayName());
    }

    private void pickFile() {
        openDocument.launch(new String[] {"*/*"});
    }

    private void onPicked(@Nullable Uri uri) {
        if (uri == null) {
            return;
        }
        String picked = uri.toString();
        prepareJob.start(app.worker(), JOB_PICK, () -> {
            SafVaultLocation where = actions.pick(picked);
            return new Prepared(where, actions.check(where), null);
        });
    }

    private void unlockWithPassword() {
        unlockWithPassword(false);
    }

    // costlyConfirmed：使用者看過預估時間並按了「仍要開啟」（C-4，CostlyKdfPrompt）
    private void unlockWithPassword(boolean costlyConfirmed) {
        if (location == null || unlockJob.isRunning()) {
            return;
        }
        char[] chars = PasswordChars.of(password.getText());
        byte[] bytes = Secrets.utf8(chars);
        Secrets.wipe(chars);
        unlock(bytes, costlyConfirmed);
    }

    /**
     * @param bytes 主密碼的 UTF-8 位元組；背景工作用完清零
     */
    private void unlock(byte[] bytes, boolean costlyConfirmed) {
        SafVaultLocation where = location;
        // 還原模式（AC-09-5）：主密碼先解開選定的備份確認可用，才備份目前的檔案並寫回，接著直接解鎖
        BackupStore.Backup restoring = banners.target();
        boolean started = unlockJob.start(app.worker(), JOB_UNLOCK, () -> {
            try {
                if (restoring != null) {
                    actions.restoreAndUnlock(where, restoring, bytes);
                } else {
                    actions.unlock(where, bytes, costlyConfirmed);
                }
                return null;
            } catch (VaultException e) {
                // 在背景算出原因：判斷有沒有備份要讀本機資料夾
                return restoring != null ? OpenFailure.ofRestore(e) : actions.failure(e, where);
            } finally {
                Secrets.wipe(bytes);
            }
        });
        if (!started) {
            Secrets.wipe(bytes);
            return;
        }
        setBusy(true);
    }

    private void setBusy(boolean busy) {
        Buttons.setLoading(submit, busy);
        status.setVisibility(busy ? View.VISIBLE : View.GONE);
        passwordLayout.setEnabled(!busy);
        other.setEnabled(!busy);
        providers.setEnabled(!busy);
        handler.removeCallbacks(slow);
        if (busy) {
            handler.postDelayed(slow, SLOW_MS);
        } else {
            overlay.hide();
        }
    }

    private void onDone(BackgroundJob source, @Nullable BackgroundJob.Outcome outcome) {
        if (outcome == null) {
            return;
        }
        source.consume();
        switch (outcome.tag()) {
            case JOB_PREPARE:
            case JOB_PICK:
                onPrepared(outcome);
                break;
            case JOB_UNLOCK:
                onUnlocked(outcome);
                break;
            default:
                break;
        }
    }

    private void onPrepared(BackgroundJob.Outcome outcome) {
        if (outcome.error() instanceof VaultIoException) {
            // 重新選擇的檔案拿不到權限或已不存在
            banners.showFailure(OpenFailure.of((VaultIoException) outcome.error(), false));
            return;
        }
        if (outcome.error() != null) {
            Log.w(TAG, "準備解鎖失敗：" + outcome.error().getClass().getName());
            return;
        }
        Prepared p = (Prepared) outcome.value();
        if (p.location() == null) {
            // 上鎖後回來，但最近開啟清單已經空了
            exits.leave();
            return;
        }
        if (location == null || !location.persistentId().equals(p.location().persistentId())) {
            location = p.location();
            // 重建（旋轉、深色模式）時沿用這個檔案，回程也保留
            setIntent(intent(this, location.persistentId(), location.displayName())
                    .putExtra(EXTRA_RETURN, exits.returning()));
            showName();
        }
        if (p.problem() == null) {
            if (!banners.reshow()) {
                banner.setVisibility(View.GONE);
            }
        } else {
            banners.showFailure(p.problem());
        }
        showProviders(p.vaultId());
    }

    private void onUnlocked(BackgroundJob.Outcome outcome) {
        setBusy(false);
        if (outcome.error() != null) {
            // 開啟只會丟 VaultException（已在背景轉成 OpenFailure）；其他是程式問題，只記類型
            Log.w(TAG, "解鎖失敗：" + outcome.error().getClass().getName());
            banners.showFailure(new OpenFailure(OpenFailure.Kind.UNAVAILABLE, R.string.unlock_error_unknown, null,
                    OpenFailure.Action.RESELECT_FILE));
            return;
        }
        OpenFailure f = (OpenFailure) outcome.value();
        if (f == null) {
            // LockGate 也會由 VaultSession 的通知改成解鎖；這裡先改，首頁（或回程的頁面）的 onStart 才不會又導回解鎖畫面
            app.lockGate().markUnlocked();
            Editable text = password.getText();
            if (text != null) {
                text.clear();
            }
            exits.unlocked(banners.target() != null);
            return;
        }
        if (f.kind() == OpenFailure.Kind.COSTLY) {
            CostlyKdfPrompt.show(this, f.messageArg(), () -> unlockWithPassword(true));
            return;
        }
        if (f.kind() == OpenFailure.Kind.WRONG_PASSWORD) {
            // AC-01-3：欄位下方「主密碼錯誤」，清空並重新聚焦
            Editable text = password.getText();
            if (text != null) {
                text.clear();
            }
            passwordLayout.setError(getString(f.messageRes()));
            password.requestFocus();
            return;
        }
        banners.showFailure(f);
    }

    private void showProviders(@Nullable String vaultId) {
        // AC-11-4：模組失敗時退回主密碼輸入
        providers.show(vaultId, bytes -> unlock(bytes, false), password::requestFocus);
    }
}
