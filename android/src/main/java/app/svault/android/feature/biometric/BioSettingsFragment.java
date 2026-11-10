package app.svault.android.feature.biometric;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.ConfirmDialogs;
import app.svault.android.ui.Toasts;
import com.google.android.material.button.MaterialButton;

/**
 * 設定頁的「生物辨識解鎖」段落（T-71、T-72；{@code SettingsSection}）：依目前解鎖的保管庫（{@code ModuleHost.unlockedVaultId()}）
 * 顯示「已啟用／尚未啟用」與「啟用」或「停用」（AC-11-1），裝置不支援時說明原因（AC-11-4），別的保管庫也有資料時另有
 * 「停用所有保管庫」（規格 §1）。要顯示什麼由 {@link BioSettingsState} 決定。
 *
 * <p>啟用的對話框與流程在 {@link BioEnableDialog}、{@link BioEnableFlow}。Keystore 與私有偏好的操作很快，在 UI 執行緒做
 * （T-70）。按鈕的動作不在外殼的錯誤邊界裡，自己接住例外並顯示 error Toast。
 */
public final class BioSettingsFragment extends Fragment {

    private TextView status;
    private TextView note;
    private MaterialButton enable;
    private MaterialButton disable;
    private MaterialButton disableAll;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.bio_settings, container, false);
        status = root.findViewById(R.id.bio_settings_status);
        note = root.findViewById(R.id.bio_settings_note);
        enable = root.findViewById(R.id.bio_settings_enable);
        disable = root.findViewById(R.id.bio_settings_disable);
        disableAll = root.findViewById(R.id.bio_settings_disable_all);
        enable.setOnClickListener(v -> BioEnableDialog.show(requireActivity(), this::refreshIfShown));
        disable.setOnClickListener(v -> confirmDisable(false));
        disableAll.setOnClickListener(v -> confirmDisable(true));
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 回到設定頁時重新讀：解鎖時金鑰可能因新增指紋而被刪除（AC-11-3）
        refresh();
    }

    private void refreshIfShown() {
        if (isAdded() && getView() != null) {
            refresh();
            // 提示框關掉後焦點不要掉到畫面開頭：移到狀態。TalkBack 不搶焦點，新的狀態由 live region 讀出（版面）
            requireView().findViewById(R.id.bio_settings_summary).requestFocus();
        }
    }

    private void refresh() {
        BioSettingsState state;
        try {
            // 每次重新取（T-35 的說明）：上鎖或換了保管庫時跟著變
            state = BioSettingsState.read(BioVault.of(requireContext()),
                    ModuleHost.of(requireContext()).unlockedVaultId(),
                    BioPrompt.strongUnavailableReason(requireContext()));
        } catch (BioException | RuntimeException e) {
            status.setText(R.string.bio_settings_status_error);
            note.setText(R.string.bio_reason_failed);
            enable.setVisibility(View.GONE);
            disable.setVisibility(View.GONE);
            disableAll.setVisibility(View.GONE);
            return;
        }
        status.setText(statusText(state));
        note.setText(state.unavailable() != null ? state.unavailable().messageRes() : R.string.bio_settings_note);
        enable.setVisibility(state.canEnable() ? View.VISIBLE : View.GONE);
        disable.setVisibility(state.canDisableCurrent() ? View.VISIBLE : View.GONE);
        disableAll.setVisibility(state.canDisableAll() ? View.VISIBLE : View.GONE);
    }

    private String statusText(BioSettingsState state) {
        @StringRes int current = state.vaultId() == null ? R.string.bio_settings_status_locked
                : state.currentEnabled() ? R.string.bio_settings_status_on : R.string.bio_settings_status_off;
        if (state.othersEnabled() == 0) {
            return getString(current);
        }
        return getString(R.string.bio_settings_status_others, getString(current), state.othersEnabled());
    }

    private void confirmDisable(boolean all) {
        ConfirmDialogs.show(requireContext(),
                getString(all ? R.string.bio_settings_disable_all_title : R.string.bio_settings_disable_title),
                getString(all ? R.string.bio_settings_disable_message : R.string.bio_settings_disable_current_message),
                getString(R.string.bio_settings_disable_confirm), true, (d, which) -> disable(all));
    }

    private void disable(boolean all) {
        View anchor = requireView();
        try {
            BioVault vault = BioVault.of(requireContext());
            if (all) {
                vault.disableAll();
            } else {
                // 確認對話框開著的期間可能已上鎖：沒有目前的保管庫時不做事
                String vaultId = ModuleHost.of(requireContext()).unlockedVaultId().orElse(null);
                if (vaultId != null) {
                    vault.disable(vaultId);
                }
            }
            Toasts.show(anchor, getString(R.string.bio_settings_disabled), Toasts.Kind.SUCCESS);
        } catch (BioException | RuntimeException e) {
            // 密文一定已刪除（disable 先刪密文）；留下的金鑰沒有密文就解不出任何東西，下次停用或啟用時清掉
            Toasts.show(anchor, getString(R.string.bio_settings_disable_failed), Toasts.Kind.ERROR);
        }
        refreshIfShown();
    }
}
