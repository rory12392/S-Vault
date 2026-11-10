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

/**
 * 自動填入的啟用引導（T-82；AC-10-1）：由外殼的 {@code FeatureHostActivity} 載入（設定段落的「設定自動填入」
 * 「自動填入說明」以 {@code ModuleHost.pageIntent} 開）。說明、目前的狀態、步驟與「前往系統設定」
 * （{@code Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE}）；每次回到前景重新偵測，從系統設定回來就換成「已是自動填入服務」。
 *
 * <p>顯示過就記下 {@code modules.autofill.guideSeen}：之後設定段落的主要按鈕直接開系統設定。按鈕的動作不在外殼的錯誤邊界裡，
 * 開不起系統設定時顯示 error Snackbar。
 */
public class AfGuideFragment extends Fragment {

    private View steps;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.af_guide, container, false);
        steps = root.findViewById(R.id.af_guide_steps);
        root.findViewById(R.id.af_guide_open).setOnClickListener(v -> {
            if (!AutofillSetup.openSystemSettings(this)) {
                Toasts.show(v, getString(R.string.af_status_open_failed), Toasts.Kind.ERROR);
            }
        });
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        AutofillSetup.markGuideSeen(requireContext());
    }

    @Override
    public void onResume() {
        super.onResume();
        // 從系統設定回來時重新偵測（使用者也可能在那裡改選別的服務）
        SetupState.Service service = AutofillSetup.service(requireContext());
        AfStatusView.bind(requireView(), service);
        steps.setVisibility(SetupState.guideOffersSystemSettings(service) ? View.VISIBLE : View.GONE);
    }
}
