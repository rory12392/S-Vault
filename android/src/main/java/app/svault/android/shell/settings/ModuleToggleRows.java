package app.svault.android.shell.settings;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import app.svault.android.R;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.core.module.ModuleToggle;
import app.svault.core.storage.Settings;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 設定頁「功能模組」區段（T-38）：探索到的每個模組一個啟用開關（停用中的也列出），寫
 * {@code modules.<id>.enabled}。模組只在 App 啟動時載入，所以改了之後下方出現「完全關閉 S-Vault 再開啟後…」；
 * 停用前 danger 確認，取消、返回、點外面都不改設定。列名稱只用 {@code displayName()}（{@code ModuleRegistry#toggles}
 * 在錯誤邊界內取得）。停用的模組下次啟動時照 T-35 收到 {@code onDisabled}（BIO 刪金鑰）。
 */
final class ModuleToggleRows {

    /** 寫入設定（{@link SettingsChanges#change}，寫檔失敗的提示由呼叫端處理）。 */
    interface Saver {
        /** @param change 從目前的設定算出新的設定 */
        void change(UnaryOperator<Settings> change);
    }

    private ModuleToggleRows() {
    }

    /**
     * @param toggle 一列的狀態
     * @return 要重新啟動才生效時的提示；不用時 0
     */
    @StringRes
    static int note(ModuleToggle toggle) {
        if (!toggle.restartNeeded()) {
            return 0;
        }
        return toggle.enabled() ? R.string.settings_modules_restart_on : R.string.settings_modules_restart_off;
    }

    /**
     * @param body    加在這裡的最後
     * @param toggles {@code ModuleRegistry#toggles} 的結果
     * @param saver   寫入設定
     */
    static void add(ViewGroup body, List<ModuleToggle> toggles, Saver saver) {
        for (ModuleToggle toggle : toggles) {
            new Row(body, toggle, saver);
        }
    }

    /** 一個模組的開關。 */
    private static final class Row {
        private final ModuleToggle initial;
        private final Saver saver;
        private final CompoundButton button;
        private final TextView note;
        private final CompoundButton.OnCheckedChangeListener onSwitch = (b, checked) -> onSwitch(checked);

        Row(ViewGroup body, ModuleToggle initial, Saver saver) {
            this.initial = initial;
            this.saver = saver;
            View row = LayoutInflater.from(body.getContext()).inflate(R.layout.settings_toggle_row, body, false);
            button = row.findViewById(R.id.settings_toggle_switch);
            note = row.findViewById(R.id.settings_toggle_note);
            button.setText(initial.name());
            // 儀器測試與畫面腳本用的 tag（id 是動態的）
            button.setTag("settings-module-" + initial.id());
            showSwitch(initial.enabled());
            showNote(initial.enabled());
            body.addView(row);
        }

        /** 換開關的狀態但不觸發 {@link #onSwitch}（程式改的不是使用者改的）。 */
        private void showSwitch(boolean on) {
            button.setOnCheckedChangeListener(null);
            button.setChecked(on);
            button.setOnCheckedChangeListener(onSwitch);
        }

        private void showNote(boolean on) {
            int res = note(new ModuleToggle(initial.id(), initial.name(), initial.running(), on));
            note.setVisibility(res == 0 ? View.GONE : View.VISIBLE);
            note.setText(res == 0 ? "" : note.getContext().getString(res));
        }

        private void apply(boolean on) {
            saver.change(s -> s.withModuleEnabled(initial.id(), on));
            showSwitch(on);
            showNote(on);
        }

        private void onSwitch(boolean on) {
            if (on) {
                apply(true);
                return;
            }
            // 確認之前開關先維持開啟：取消、返回、點外面都不改設定
            showSwitch(true);
            Context context = button.getContext();
            boolean[] confirmed = {false};
            AlertDialog dialog = ConfirmDialogs.show(context,
                    context.getString(R.string.settings_modules_disable_title, initial.name()),
                    context.getString(R.string.settings_modules_disable_message),
                    context.getString(R.string.settings_modules_disable_confirm), true, (d, which) -> {
                        confirmed[0] = true;
                        apply(false);
                    });
            dialog.setOnDismissListener(d -> {
                if (!confirmed[0]) {
                    showSwitch(true);
                }
            });
        }
    }
}
