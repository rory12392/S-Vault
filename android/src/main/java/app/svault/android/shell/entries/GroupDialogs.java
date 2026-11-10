package app.svault.android.shell.entries;

import android.content.Context;
import android.content.DialogInterface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import app.svault.android.R;
import app.svault.android.ui.SecureWindows;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.function.Consumer;

/**
 * 新增群組、重新命名群組的對話框（標題「新增群組」／「重新命名群組」＋欄位「群組名稱」＋
 * 「新增」／「重新命名」；空白時「群組名稱不能是空的」，對話框不關）。
 */
final class GroupDialogs {

    private GroupDialogs() {
    }

    /**
     * @param context  畫面
     * @param rename   重新命名時為 {@code true}
     * @param initial  欄位的初始值（重新命名時是目前的名稱）
     * @param onName   按下確認且名稱不是空白時（已去掉前後空白）
     */
    static void showName(Context context, boolean rename, String initial, Consumer<String> onName) {
        View content = LayoutInflater.from(context).inflate(R.layout.entries_name_dialog, null, false);
        TextInputLayout layout = content.findViewById(R.id.name_layout);
        TextInputEditText input = content.findViewById(R.id.name_input);
        input.setText(initial);
        input.setSelection(input.length());
        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.ThemeOverlay_SVault_Dialog)
                .setTitle(rename ? R.string.entries_name_rename_title : R.string.entries_name_add_title)
                .setView(content)
                .setNegativeButton(R.string.sv_dialog_cancel, null)
                .setPositiveButton(rename ? R.string.entries_name_rename : R.string.entries_name_add, null)
                .create();
        dialog.setOnShowListener(d -> {
            Button ok = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            // 預設的按鈕會先關掉對話框；改成驗證通過才關
            ok.setOnClickListener(v -> {
                String name = input.getText() == null ? "" : input.getText().toString().trim();
                if (name.isEmpty()) {
                    layout.setError(context.getString(R.string.entries_name_error));
                    input.requestFocus();
                    return;
                }
                dialog.dismiss();
                onName.accept(name);
            });
            input.requestFocus();
        });
        input.addTextChangedListener(new SimpleWatcher(() -> layout.setError(null)));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        // 欄位裡是群組名稱（AC-12-4）
        SecureWindows.inherit(dialog).show();
    }

    /** 文字改變時收掉錯誤（改了欄位就收掉該欄的錯誤）。 */
    static final class SimpleWatcher implements android.text.TextWatcher {

        private final Runnable onChange;

        SimpleWatcher(Runnable onChange) {
            this.onChange = onChange;
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(android.text.Editable s) {
            onChange.run();
        }
    }
}
