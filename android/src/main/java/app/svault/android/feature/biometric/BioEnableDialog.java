package app.svault.android.feature.biometric;

import android.content.DialogInterface;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.SecretEditText;
import app.svault.android.ui.SecureWindows;
import app.svault.android.ui.Toasts;
import app.svault.core.module.MasterPasswordCheck;
import app.svault.core.shell.Secrets;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputLayout;

/**
 * 設定頁「啟用生物辨識解鎖」的對話框（T-72；AC-11-1 的啟用）：輸入主密碼 →「下一步」→ 外殼確認（D-165）→ 關掉對話框、
 * 出現生物辨識提示框 → 成功時 success Toast。流程與清零在 {@link BioEnableFlow}。
 *
 * <p>主密碼從欄位取成字元陣列（不經過 {@code String}）、轉成 UTF-8 後立即清掉字元與欄位，位元組交給流程；不寫入任何欄位、
 * 日誌或 {@code Bundle}。主密碼不正確時對話框不關、欄位顯示錯誤；同一次解鎖錯太多次時「下一步」停用。
 */
final class BioEnableDialog {

    private final FragmentActivity activity;
    private final Runnable onChanged;
    private final View anchor;
    private AlertDialog dialog;
    private TextInputLayout layout;
    private SecretEditText input;
    private Button next;

    private BioEnableDialog(FragmentActivity activity, Runnable onChanged) {
        this.activity = activity;
        this.onChanged = onChanged;
        this.anchor = activity.findViewById(android.R.id.content);
    }

    /**
     * @param activity  設定頁
     * @param onChanged 流程結束（啟用、取消或失敗）後重新整理段落
     */
    static void show(FragmentActivity activity, Runnable onChanged) {
        new BioEnableDialog(activity, onChanged).build();
    }

    private void build() {
        View content = LayoutInflater.from(activity).inflate(R.layout.bio_enable_dialog, null, false);
        layout = content.findViewById(R.id.bio_enable_password_layout);
        input = content.findViewById(R.id.bio_enable_password);
        dialog = new MaterialAlertDialogBuilder(activity, R.style.ThemeOverlay_SVault_Dialog)
                .setTitle(R.string.bio_enable_title)
                .setMessage(R.string.bio_enable_message)
                .setView(content)
                .setNegativeButton(R.string.sv_dialog_cancel, null)
                .setPositiveButton(R.string.bio_enable_next, null)
                .create();
        dialog.setOnShowListener(d -> {
            next = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            // 預設的按鈕會先關掉對話框；改成確認通過才關
            next.setOnClickListener(v -> submit());
            input.requestFocus();
        });
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit();
                return true;
            }
            return false;
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // 改了欄位就收掉錯誤；不讀內容
                if (s.length() > 0) {
                    layout.setError(null);
                }
            }
        });
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        // 欄位裡是主密碼：不論設定頁有沒有 FLAG_SECURE，對話框一律防截圖
        SecureWindows.secure(dialog.getWindow());
        dialog.show();
    }

    private void submit() {
        Editable text = input.getText();
        if (text == null || text.length() == 0) {
            layout.setError(activity.getString(R.string.bio_enable_empty));
            return;
        }
        char[] chars = new char[text.length()];
        text.getChars(0, chars.length, chars, 0);
        byte[] password = Secrets.utf8(chars);
        Secrets.wipe(chars);
        text.clear();
        setBusy(true);
        layout.setError(null);
        new BioEnableFlow(BioVault.of(activity), BioPrompt.strongUnavailableReason(activity),
                (pw, result) -> ModuleHost.of(activity).verifyMasterPassword(BiometricAndroidFeature.ID, pw,
                        activity.getMainExecutor(), result),
                (cipher, cb) -> BioPrompt.authenticate(activity, cipher, R.string.bio_prompt_title_enable, cb),
                new Results()).start(password);
    }

    private void setBusy(boolean busy) {
        input.setEnabled(!busy);
        if (next != null) {
            next.setEnabled(!busy);
        }
    }

    /**
     * 啟用失敗時的說明（AC-11-4）。解鎖用的原因多半寫「請使用主密碼解鎖」，在設定頁不合適；只有裝置本身的狀況照用。
     *
     * @param reason 原因
     * @return 字串資源
     */
    static int failureMessage(BioReason reason) {
        return switch (reason) {
            case NONE_ENROLLED, HARDWARE_UNAVAILABLE -> reason.messageRes();
            case LOCKOUT, LOCKOUT_PERMANENT -> R.string.bio_enable_lockout;
            default -> R.string.bio_enable_failed;
        };
    }

    private boolean gone() {
        return activity.isFinishing() || activity.isDestroyed();
    }

    /** 流程的結果（主執行緒）。 */
    private final class Results implements BioEnableFlow.Listener {

        @Override
        public void onVerified() {
            dialog.dismiss();
        }

        @Override
        public void onRejected(MasterPasswordCheck.Status status) {
            if (gone()) {
                return;
            }
            switch (status) {
                case MISMATCH -> fieldError(R.string.bio_enable_mismatch, true);
                // 同一次解鎖錯 5 次：再試也一樣，「下一步」與欄位維持停用，只能取消
                case TOO_MANY_ATTEMPTS -> fieldError(R.string.bio_enable_too_many, false);
                case FAILED -> fieldError(R.string.bio_enable_check_failed, true);
                // 已上鎖：畫面本來就會導回解鎖畫面
                default -> dialog.dismiss();
            }
        }

        private void fieldError(int message, boolean retry) {
            setBusy(!retry);
            layout.setError(activity.getString(message));
            if (retry) {
                input.requestFocus();
            }
        }

        @Override
        public void onEnabled(String vaultId) {
            done(R.string.bio_enable_done, Toasts.Kind.SUCCESS);
        }

        @Override
        public void onCanceled() {
            done(R.string.bio_enable_canceled, Toasts.Kind.INFO);
        }

        @Override
        public void onFailed(BioReason reason) {
            dialog.dismiss();
            done(failureMessage(reason), Toasts.Kind.ERROR);
        }

        private void done(int message, Toasts.Kind kind) {
            if (gone()) {
                return;
            }
            Toasts.show(anchor, activity.getString(message), kind);
            onChanged.run();
        }
    }
}
