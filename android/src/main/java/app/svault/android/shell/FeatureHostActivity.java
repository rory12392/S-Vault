package app.svault.android.shell;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.start.UnlockActivity;
import app.svault.android.ui.EmptyState;
import app.svault.android.ui.SystemBars;
import app.svault.core.module.ModuleGuard;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.Optional;

/**
 * 以 Fragment 類別載入功能模組的畫面（{@code MenuPage}、{@code SettingsSection}、AF 的條目選擇與啟用引導；
 * Architecture §1.1），模組不需要各自宣告 Activity。繼承 {@link SecureActivity}，所以一樣有 {@code FLAG_SECURE}
 * 與上鎖導回。
 *
 * <p>頁面建立（含第一次建立畫面）包在 {@link ModuleGuard} 裡：失敗時顯示錯誤狀態，其他功能照常。重建畫面時模組的
 * 頁面也不由 FragmentManager 還原，而是重新建立、一樣在錯誤邊界內（{@link ModuleRestoreFactory}，T-22）。
 * 頁面要回傳結果時呼叫 {@link #deliverResult(Fragment, Bundle)}，結果成為這個 Activity 的 {@code RESULT_OK} 資料。
 *
 * <p>模組經 {@code ModuleHost} 自己開的頁面（{@link ModulePages}，T-36）遇到上鎖時走回程（{@link #onLocked()}）：
 * 解鎖後回到這一頁才載入 Fragment，不清掉工作。
 */
public class FeatureHostActivity extends SecureActivity {

    static final String EXTRA_FRAGMENT = "app.svault.android.extra.FRAGMENT";
    static final String EXTRA_TITLE = "app.svault.android.extra.TITLE";
    static final String EXTRA_TITLE_TEXT = "app.svault.android.extra.TITLE_TEXT";
    static final String EXTRA_MODULE = "app.svault.android.extra.MODULE";
    static final String EXTRA_ARGS = "app.svault.android.extra.ARGS";
    /** 模組自己開的頁面（{@link ModulePages}，T-36）：上鎖時解鎖後回到這一頁，不清掉工作。 */
    static final String EXTRA_RETURN = "app.svault.android.extra.RETURN_AFTER_UNLOCK";
    static final String RESULT_KEY = "app.svault.android.featureResult";
    private static final String STATE_AWAITING = "awaitingUnlock";
    private static final String STATE_SHOWN = "shownUnlocked";

    private boolean pendingLoad;
    // 回程（T-36）：正在等解鎖畫面的結果；頁面已經在解鎖的狀態下載入過（之後上鎖就只結束自己）
    private boolean awaitingUnlock;
    private boolean shownUnlocked;
    private ActivityResultLauncher<Intent> unlock;

    /**
     * @param context  目前的畫面
     * @param moduleId 模組代號（錯誤紀錄用）
     * @param titleRes 頁面標題
     * @param page     頁面的 Fragment（public、有 public 無參數建構子）
     * @param args     給頁面的參數；沒有時為 {@code null}
     * @return 開啟頁面的 Intent（用 {@code startActivity} 或 Activity Result API 啟動）
     */
    public static Intent intent(Context context, String moduleId, @StringRes int titleRes,
            Class<? extends Fragment> page, @Nullable Bundle args) {
        return new Intent(context, FeatureHostActivity.class)
                .putExtra(EXTRA_MODULE, moduleId)
                .putExtra(EXTRA_TITLE, titleRes)
                .putExtra(EXTRA_FRAGMENT, page.getName())
                .putExtra(EXTRA_ARGS, args);
    }

    /**
     * 選單的模組頁面（T-30，{@link ModuleMenus}）：標題已經是文字（模組的字串資源或模組名稱）。
     *
     * @param context   目前的畫面
     * @param moduleId  模組代號（錯誤紀錄用）
     * @param title     頁面標題
     * @param pageClass 頁面的 Fragment 類別名稱；{@code null} 或載入不了時顯示錯誤狀態
     * @return 開啟頁面的 Intent
     */
    public static Intent intent(Context context, String moduleId, CharSequence title, @Nullable String pageClass) {
        return new Intent(context, FeatureHostActivity.class)
                .putExtra(EXTRA_MODULE, moduleId)
                .putExtra(EXTRA_TITLE_TEXT, title)
                .putExtra(EXTRA_FRAGMENT, pageClass);
    }

    /**
     * 由頁面呼叫：關閉頁面並把結果交給開啟它的畫面。
     *
     * @param page   目前的頁面
     * @param result 結果（不放主密碼等機密）
     */
    public static void deliverResult(Fragment page, Bundle result) {
        page.getParentFragmentManager().setFragmentResult(RESULT_KEY, result);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // 重建時模組的 Fragment 不由 FragmentManager 還原（還原不在錯誤邊界內），先換成空白的佔位、onStart 再重新載入
        ModuleRestoreFactory restore = new ModuleRestoreFactory();
        getSupportFragmentManager().setFragmentFactory(restore);
        restore.restoring(savedInstanceState != null);
        try {
            super.onCreate(savedInstanceState);
        } finally {
            restore.restoring(false);
        }
        unlock = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), this::onUnlockResult);
        if (savedInstanceState != null) {
            awaitingUnlock = savedInstanceState.getBoolean(STATE_AWAITING);
            shownUnlocked = savedInstanceState.getBoolean(STATE_SHOWN);
        }
        setContentView(R.layout.activity_feature_host);
        SystemBars.applyInsets(findViewById(R.id.sv_host_root));
        MaterialToolbar toolbar = findViewById(R.id.sv_host_toolbar);
        int title = getIntent().getIntExtra(EXTRA_TITLE, 0);
        CharSequence titleText = getIntent().getCharSequenceExtra(EXTRA_TITLE_TEXT);
        if (title != 0) {
            toolbar.setTitle(title);
        } else if (titleText != null) {
            toolbar.setTitle(titleText);
        }
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        getSupportFragmentManager().setFragmentResultListener(RESULT_KEY, this, (key, result) -> {
            setResult(Activity.RESULT_OK, new Intent().putExtras(result));
            finish();
        });
        // 重建時（旋轉、深色模式切換）外殼的頁面由 FragmentManager 還原；模組的頁面是佔位、上次載入失敗時是空的，
        // 這兩種都在 onStart 重新載入（失敗的會再顯示一次錯誤狀態，不會變成空白頁）
        Fragment restored = getSupportFragmentManager().findFragmentById(R.id.sv_host_container);
        pendingLoad = restored == null || restored instanceof PendingModuleFragment;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 在 onStart 才載入：此時 commitNow 會同步跑到頁面的 onCreateView，建立畫面的例外也在錯誤邊界內。
        // 回程等解鎖時不載入：解鎖畫面的結果在 onStart 之後才到（onUnlockResult）
        if (pendingLoad && !isFinishing() && !awaitingUnlock) {
            pendingLoad = false;
            load();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_AWAITING, awaitingUnlock);
        outState.putBoolean(STATE_SHOWN, shownUnlocked);
    }

    /**
     * 上鎖（開啟時已上鎖、或顯示中被上鎖）。一般頁面照 {@link SecureActivity}：整個工作換成解鎖畫面。模組自己開的頁面
     * （回程）不清掉工作——它可能在別的 App 的工作裡（AF 的驗證流程）：還沒顯示過內容時在同一個工作裡開解鎖畫面、等結果；
     * 已經顯示過時只以 {@code RESULT_CANCELED} 結束自己。
     */
    @Override
    public void onLocked() {
        if (!getIntent().getBooleanExtra(EXTRA_RETURN, false)) {
            super.onLocked();
            return;
        }
        if (isFinishing() || awaitingUnlock) {
            return;
        }
        if (shownUnlocked) {
            cancel();
            return;
        }
        awaitingUnlock = true;
        unlock.launch(UnlockActivity.returnIntent(this));
    }

    private void onUnlockResult(ActivityResult result) {
        awaitingUnlock = false;
        if (result.getResultCode() != Activity.RESULT_OK || lockGate().isLocked()) {
            // 返回、改開其他檔案、沒有可解鎖的保管庫；或解鎖後又立刻被上鎖
            cancel();
            return;
        }
        if (pendingLoad && !isFinishing()) {
            pendingLoad = false;
            load();
        }
    }

    private void cancel() {
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private void load() {
        shownUnlocked = true;
        // 和擴充點用同一個錯誤邊界（T-30）：頁面出錯一樣記錄並通知外殼
        ModuleGuard guard = ((SvaultApplication) getApplication()).modules().guard();
        String moduleId = Optional.ofNullable(getIntent().getStringExtra(EXTRA_MODULE)).orElse("?");
        Optional<Class<? extends Fragment>> page = FeatureFragments.resolve(
                getIntent().getStringExtra(EXTRA_FRAGMENT), getClassLoader());
        if (!page.isPresent()) {
            guard.report(moduleId, "openPage", new IllegalArgumentException("不是可載入的頁面"));
            showError();
            return;
        }
        FragmentManager fm = getSupportFragmentManager();
        boolean ok = guard.run(moduleId, "openPage", () -> {
            Fragment f = fm.getFragmentFactory().instantiate(getClassLoader(), page.get().getName());
            f.setArguments(getIntent().getBundleExtra(EXTRA_ARGS));
            fm.beginTransaction().replace(R.id.sv_host_container, f).commitNow();
        });
        if (!ok) {
            // 拿掉建立到一半的頁面，畫面只留錯誤狀態
            guard.run(moduleId, "removePage", () -> {
                Fragment broken = fm.findFragmentById(R.id.sv_host_container);
                if (broken != null) {
                    fm.beginTransaction().remove(broken).commitNowAllowingStateLoss();
                }
            });
            showError();
        }
    }

    private void showError() {
        findViewById(R.id.sv_host_container).setVisibility(View.GONE);
        EmptyState error = findViewById(R.id.sv_host_error);
        error.show(R.drawable.sv_ic_error, getString(R.string.sv_feature_failed_title),
                getString(R.string.sv_feature_failed_body), null, null);
        error.setVisibility(View.VISIBLE);
    }
}
