package app.svault.android.shell.start;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.TextView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.widget.NestedScrollView;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.platform.OpenFailure;
import app.svault.android.shell.entries.HomeActivity;
import app.svault.android.ui.Banner;
import app.svault.android.ui.Buttons;
import app.svault.android.ui.StrengthMeter;
import app.svault.android.ui.SystemBars;
import app.svault.core.security.PasswordStrength;
import app.svault.core.shell.Secrets;
import app.svault.core.storage.VaultIoException;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.List;

/**
 * 建立保管庫（AC-01-1、AC-01-4、AC-01-5）。
 *
 * <p>流程：驗證表單 → 另存新檔選擇器（{@code ACTION_CREATE_DOCUMENT}，預設檔名＝名稱＋{@code .kdbx}）→ 背景寫出第一版
 * 並接手同一個保管庫 → 首頁。選擇器一選就在 Drive 建立空檔案，所以放在驗證之後（D-56）；寫出失敗時刪掉它。
 * 不顯示保管庫內容，所以不設 {@code FLAG_SECURE}；主密碼可切換明文，不讓系統做「最近使用的 App」縮圖。
 */
public class CreateVaultActivity extends AppCompatActivity {

    private static final String TAG = "SVault";
    private static final String JOB_CREATE = "create";
    private static final String JOB_DISCARD = "discard";
    /** 另存新檔選擇器建立的檔案類型；Drive 對未知副檔名本來就當成這個。 */
    private static final String KDBX_MIME = "application/octet-stream";

    private SvaultApplication app;
    private BackgroundJob job;
    private Banner banner;
    private NestedScrollView scroll;
    private TextInputLayout nameLayout;
    private TextInputEditText name;
    private TextInputLayout passwordLayout;
    private TextInputEditText password;
    private TextInputLayout confirmLayout;
    private TextInputEditText confirm;
    private StrengthMeter meter;
    private TextView weak;
    private MaterialCheckBox acknowledge;
    private TextView acknowledgeError;
    private MaterialButton submit;
    private TextView status;
    private ActivityResultLauncher<String> createDocument;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false);
        }
        setContentView(R.layout.activity_create_vault);
        SystemBars.applyInsets(findViewById(R.id.create_root));
        app = (SvaultApplication) getApplication();
        if (app.startActions() == null) {
            finish();
            return;
        }
        job = new ViewModelProvider(this).get(BackgroundJob.class);

        MaterialToolbar toolbar = findViewById(R.id.create_toolbar);
        toolbar.setNavigationOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        banner = findViewById(R.id.create_banner);
        scroll = findViewById(R.id.create_scroll);
        nameLayout = findViewById(R.id.create_name_layout);
        name = findViewById(R.id.create_name);
        passwordLayout = findViewById(R.id.create_password_layout);
        password = findViewById(R.id.create_password);
        confirmLayout = findViewById(R.id.create_confirm_layout);
        confirm = findViewById(R.id.create_confirm);
        meter = findViewById(R.id.create_meter);
        weak = findViewById(R.id.create_weak);
        acknowledge = findViewById(R.id.create_acknowledge);
        acknowledgeError = findViewById(R.id.create_acknowledge_error);
        submit = findViewById(R.id.create_submit);
        status = findViewById(R.id.create_status);

        if (savedInstanceState == null) {
            name.setText(R.string.create_name_default);
        }
        // 改了欄位就收掉該欄的錯誤（與 desktop 相同）
        clearErrorOnEdit(name, nameLayout);
        clearErrorOnEdit(confirm, confirmLayout);
        password.addTextChangedListener(new Watcher(() -> {
            passwordLayout.setError(null);
            confirmLayout.setError(null);
            updateStrength();
        }));
        acknowledge.setOnCheckedChangeListener((b, checked) -> showAcknowledgeError(null));
        confirm.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        submit.setOnClickListener(v -> submit());

        createDocument = registerForActivityResult(new ActivityResultContracts.CreateDocument(KDBX_MIME),
                this::onLocationChosen);
        job.result().observe(this, this::onDone);
        if (job.isRunning()) {
            setBusy(true);
        }
        // zxcvbn 的字典在第一次評估時載入：先在背景暖機，輸入主密碼時就不會卡一下
        app.worker().execute(() -> app.strength().estimate(""));
    }

    private void updateStrength() {
        Editable text = password.getText();
        if (text == null || text.length() == 0) {
            meter.setLevel(StrengthMeter.Level.NONE);
            weak.setVisibility(View.GONE);
            return;
        }
        // 直接評估 Editable，不轉成 String（StrengthEstimator 不保留內容）
        PasswordStrength s = app.strength().estimate(text);
        switch (s) {
            case WEAK:
                meter.setLevel(StrengthMeter.Level.WEAK);
                break;
            case MEDIUM:
                meter.setLevel(StrengthMeter.Level.MEDIUM);
                break;
            default:
                meter.setLevel(StrengthMeter.Level.STRONG);
                break;
        }
        // 弱只提醒、不阻擋
        weak.setVisibility(s == PasswordStrength.WEAK ? View.VISIBLE : View.GONE);
    }

    /** @return 表單目前的內容；主密碼陣列由呼叫端清零 */
    private CreateForm form() {
        return new CreateForm(text(name), PasswordChars.of(password.getText()), PasswordChars.of(confirm.getText()),
                acknowledge.isChecked());
    }

    private void submit() {
        if (job.isRunning()) {
            return;
        }
        CreateForm form = form();
        try {
            if (!showProblems(form.validate())) {
                return;
            }
        } finally {
            Secrets.wipe(form.password());
            Secrets.wipe(form.confirm());
        }
        banner.setVisibility(View.GONE);
        createDocument.launch(CreateForm.fileName(form.name()));
    }

    private void onLocationChosen(@Nullable Uri uri) {
        if (uri == null) {
            // 取消選擇器：留在表單，什麼都沒建立
            return;
        }
        String target = uri.toString();
        CreateForm form = form();
        List<CreateForm.Problem> problems = form.validate();
        byte[] bytes = form.takePassword();
        if (bytes == null) {
            // 選擇器開著時 Activity 被系統重建，主密碼欄不保存（saveEnabled=false）：收掉剛建立的空檔案，請使用者重填
            job.start(app.worker(), JOB_DISCARD, () -> app.documents().deleteDocument(target));
            showProblems(problems);
            return;
        }
        StartActions actions = app.startActions();
        String vaultName = form.name();
        boolean started = job.start(app.worker(), JOB_CREATE, () -> {
            try {
                return actions.create(vaultName, target, bytes);
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

    private void onDone(@Nullable BackgroundJob.Outcome outcome) {
        if (outcome == null) {
            return;
        }
        job.consume();
        if (JOB_DISCARD.equals(outcome.tag())) {
            return;
        }
        setBusy(false);
        if (outcome.error() == null && outcome.value() == StartActions.CreateOutcome.CREATED) {
            app.lockGate().markUnlocked();
            startActivity(new Intent(this, HomeActivity.class).putExtra(HomeActivity.EXTRA_CREATED, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            finish();
            return;
        }
        CharSequence message;
        if (outcome.error() == null) {
            // NOT_EMPTY：選到已有內容的檔案，不覆寫（D-52）
            message = getString(R.string.create_error_exists);
        } else if (outcome.error() instanceof VaultIoException
                && ((VaultIoException) outcome.error()).reason() != VaultIoException.Reason.IO) {
            // 拿不到權限或檔案不見了：和開檔相同的說明
            OpenFailure f = OpenFailure.of((VaultIoException) outcome.error(), false);
            message = getString(f.messageRes());
        } else {
            Log.w(TAG, "建立保管庫失敗：" + outcome.error().getClass().getName());
            message = getString(R.string.create_error_failed);
        }
        banner.show(Banner.Kind.ERROR, message, null, null);
        banner.setVisibility(View.VISIBLE);
        scroll.smoothScrollTo(0, 0);
    }

    /**
     * @param problems 驗證結果
     * @return 沒有錯誤時 {@code true}；有錯誤時顯示在各欄位並把焦點移到第一個
     */
    private boolean showProblems(List<CreateForm.Problem> problems) {
        nameLayout.setError(null);
        passwordLayout.setError(null);
        confirmLayout.setError(null);
        showAcknowledgeError(null);
        for (CreateForm.Problem p : problems) {
            String message = getString(p.messageRes());
            switch (p.field()) {
                case NAME:
                    nameLayout.setError(message);
                    break;
                case PASSWORD:
                    passwordLayout.setError(message);
                    break;
                case CONFIRM:
                    confirmLayout.setError(message);
                    break;
                default:
                    showAcknowledgeError(message);
                    break;
            }
        }
        if (problems.isEmpty()) {
            return true;
        }
        View first = fieldView(problems.get(0).field());
        first.requestFocus();
        scroll.post(() -> scroll.smoothScrollTo(0, Math.max(0, first.getTop() - scroll.getPaddingTop())));
        return false;
    }

    private View fieldView(CreateForm.Field field) {
        switch (field) {
            case NAME:
                return name;
            case PASSWORD:
                return password;
            case CONFIRM:
                return confirm;
            default:
                return acknowledge;
        }
    }

    private void showAcknowledgeError(@Nullable String message) {
        acknowledgeError.setText(message);
        acknowledgeError.setVisibility(message == null ? View.GONE : View.VISIBLE);
        // TalkBack 在核取方塊上讀出錯誤（CONVENTIONS：錯誤訊息設定在欄位上）
        acknowledge.setErrorShown(message != null);
        acknowledge.setErrorAccessibilityLabel(message);
    }

    private void setBusy(boolean busy) {
        Buttons.setLoading(submit, busy);
        status.setVisibility(busy ? View.VISIBLE : View.GONE);
        nameLayout.setEnabled(!busy);
        passwordLayout.setEnabled(!busy);
        confirmLayout.setEnabled(!busy);
        acknowledge.setEnabled(!busy);
    }

    private static String text(TextInputEditText field) {
        Editable e = field.getText();
        return e == null ? "" : e.toString();
    }

    private static void clearErrorOnEdit(TextInputEditText field, TextInputLayout layout) {
        field.addTextChangedListener(new Watcher(() -> layout.setError(null)));
    }

    /** 只關心「內容變了」的 TextWatcher。 */
    private static final class Watcher implements TextWatcher {

        private final Runnable onChange;

        Watcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
            onChange.run();
        }

        @Override
        public void afterTextChanged(Editable s) {
        }
    }
}
