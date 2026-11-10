package app.svault.android.feature.autofill;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.EmptyState;
import app.svault.core.module.ModuleGuard;
import app.svault.core.vault.EntryView;
import java.util.Optional;

/**
 * 自動填入的驗證頁（AC-10-2，T-81）：上鎖時建議清單的「S-Vault 已上鎖」選了之後，由外殼的 {@code FeatureHostActivity}
 * 載入（上鎖時外殼先導向解鎖畫面）。等這次解鎖的內容載入好，用 {@link AuthArgs} 重新比對：有符合的條目就把
 * {@code FillResponse} 交回系統（{@link AutofillManager#EXTRA_AUTHENTICATION_RESULT}），系統接著顯示這些條目；沒有符合時
 * 顯示說明與「關閉」，不交回任何帳密（AC-10-5）。T-110（D-197）起每筆建議也開這一頁（參數帶條目代號）：解鎖中直接交回
 * 那一筆帶值的項目，上鎖時一樣先解鎖；交回的回應裡每筆建議同樣不帶值。
 *
 * <p>結果直接設在 Activity 上（{@code setResult}＋{@code finish}）：系統的驗證流程讀的是這個 Activity 的結果，外殼的
 * {@code FeatureHostActivity.deliverResult} 在 {@code shell} 套件，模組不能用。查到的條目只用來建 {@code FillResponse}，
 * 不存在欄位裡、不寫日誌。
 */
public class AfAuthFragment extends Fragment {

    private final ModuleGuard guard = new ModuleGuard((moduleId, action, error) -> { });
    private View loading;
    private EmptyState empty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.af_auth, container, false);
        loading = root.findViewById(R.id.af_auth_loading);
        empty = root.findViewById(R.id.af_auth_empty);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Optional<AuthArgs> args = AuthArgs.from(getArguments());
        if (args.isEmpty()) {
            // 不是由本模組的驗證項目開的：沒有東西可以比對
            finish(Activity.RESULT_CANCELED, null);
            return;
        }
        // 已上鎖時不會通知（回傳 false）：外殼本來就會把這個畫面導回解鎖畫面；等待中上鎖也一樣
        ModuleHost.of(requireContext()).whenEntriesLoaded(requireContext().getMainExecutor(),
                () -> guard.run(AutofillAndroidFeature.ID, "authResult", () -> reply(args.get())));
    }

    private void reply(AuthArgs args) {
        if (!isAdded() || getView() == null) {
            return;
        }
        ModuleHost host = ModuleHost.of(requireContext());
        Optional<FillPlan<AutofillId>> plan = new AutofillRequests(guard, requireContext().getPackageName(),
                new PackageSigners(requireContext()), AutofillAndroidFeature.pins(requireContext()))
                .afterUnlock(args.fields(), args.packageName(), () -> AutofillAndroidFeature.enabled(requireContext()),
                        host::unlockedEntries);
        if (plan.isEmpty() || plan.get().outcome() == FillPlan.Outcome.LOCKED) {
            // 出錯（已由邊界記錄）或在通知之後又上鎖：交回「取消」，系統下次點欄位會再問一次
            finish(Activity.RESULT_CANCELED, null);
            return;
        }
        if (args.entryId() != null) {
            replyEntry(args, plan.get());
            return;
        }
        FillResponse response = FillResponses.build(requireContext(), plan.get(),
                SVaultAutofillService.entrySenders(requireContext(), args));
        if (response == null) {
            showNoMatch();
            return;
        }
        finish(Activity.RESULT_OK, new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response));
    }

    /**
     * 單筆建議（T-110，D-197）：這一筆仍在重新比對的結果裡（仍解鎖、仍符合、沒被刪）才交回帶值的項目；否則顯示沒有符合。
     */
    private void replyEntry(AuthArgs args, FillPlan<AutofillId> plan) {
        Optional<EntryView> entry = plan.outcome() != FillPlan.Outcome.ENTRIES ? Optional.empty()
                : plan.entries().stream().filter(e -> e.uuid().toString().equals(args.entryId())).findFirst();
        Dataset dataset = entry.map(e -> FillResponses.dataset(requireContext(), args.fields(), e)).orElse(null);
        if (dataset == null) {
            showNoMatch();
            return;
        }
        finish(Activity.RESULT_OK, new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset));
    }

    private void showNoMatch() {
        loading.setVisibility(View.GONE);
        empty.show(R.drawable.sv_ic_search, getString(R.string.af_auth_none_title),
                getString(R.string.af_auth_none_body), getString(R.string.af_auth_close),
                v -> finish(Activity.RESULT_CANCELED, null));
        empty.setVisibility(View.VISIBLE);
    }

    private void finish(int resultCode, @Nullable Intent data) {
        FragmentActivity activity = getActivity();
        if (activity != null && !activity.isFinishing()) {
            activity.setResult(resultCode, data);
            activity.finish();
        }
    }
}
