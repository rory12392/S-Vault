package app.svault.android.feature.autofill;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import app.svault.android.R;
import app.svault.android.ui.Toasts;
import com.google.android.material.button.MaterialButton;

/**
 * 設定頁的「自動填入」段落（T-82；{@code SettingsSection}、AC-10-1）：目前的狀態（每次回到前景重新偵測）、主要按鈕
 * （{@link SetupState#primary()}：還沒看過引導開引導頁，看過直接開系統設定）、「自動填入說明」。
 *
 * <p>模組的開關只有外殼「功能模組」區段那一個（T-38，M-03 整合決定）：同一頁兩個開關寫同一個鍵不會互相更新，所以這裡只說明去哪裡關。
 * 按鈕的動作不在外殼的錯誤邊界裡，開不起畫面時顯示 error Snackbar。
 */
public final class AfSettingsFragment extends Fragment {

    private MaterialButton primary;
    private MaterialButton guide;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.af_settings, container, false);
        primary = root.findViewById(R.id.af_settings_primary);
        guide = root.findViewById(R.id.af_settings_guide);
        guide.setOnClickListener(this::openGuide);
        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 從系統設定或引導頁回來時重新偵測
        refresh();
    }

    private void refresh() {
        SetupState state = AutofillSetup.state(requireContext());
        AfStatusView.bind(requireView(), state.service());
        switch (state.primary()) {
            case OPEN_GUIDE -> showPrimary(R.string.af_settings_setup, this::openGuide);
            case OPEN_SYSTEM_SETTINGS -> showPrimary(R.string.af_settings_open_system, v -> {
                if (!AutofillSetup.openSystemSettings(this)) {
                    Toasts.show(v, getString(R.string.af_status_open_failed), Toasts.Kind.ERROR);
                }
            });
            default -> primary.setVisibility(View.GONE);
        }
        guide.setVisibility(state.showGuideLink() ? View.VISIBLE : View.GONE);
    }

    private void showPrimary(int textRes, View.OnClickListener action) {
        primary.setText(textRes);
        primary.setOnClickListener(action);
        primary.setVisibility(View.VISIBLE);
    }

    private void openGuide(View anchor) {
        if (!AutofillSetup.openGuide(this)) {
            Toasts.show(anchor, getString(R.string.af_status_open_failed), Toasts.Kind.ERROR);
        }
    }
}
