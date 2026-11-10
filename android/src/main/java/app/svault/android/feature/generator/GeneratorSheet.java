package app.svault.android.feature.generator;

import android.app.Dialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.shell.ext.PasswordFill;
import app.svault.android.ui.SecureWindows;
import app.svault.android.ui.Toasts;
import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.module.ModuleGuard;
import java.util.Optional;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

/**
 * 編輯畫面密碼欄旁「產生」的底部 sheet（T-44）：內容和產生器頁相同（{@link GeneratorPanel}），
 * 「使用這個密碼」把結果交給外殼的 {@link PasswordFill} 回填密碼欄後關閉；「取消」、返回、往下滑關閉都不回填。
 *
 * <p>回填物件只存在這個 sheet 自己的欄位（D-170）：不放 Bundle（會進狀態保存）、ViewModel 或靜態欄位。
 * 編輯畫面重建（旋轉、換主題、換字體）時外殼不還原這個 sheet，它直接消失、舊的回填失效，使用者再按一次「產生」；被上鎖時
 * 編輯畫面結束，sheet 跟著關閉。
 *
 * <p>狀態在 sheet 範圍的 {@link GeneratorViewModel}：選項照產生器頁記在 {@code modules.generator.*}，每次打開都是新的結果，
 * sheet 關閉時清掉。對話框是另一個視窗，一律設 {@code FLAG_SECURE}（{@link SecureWindows}）。產生的密碼不記錄日誌。
 */
public class GeneratorSheet extends BottomSheetDialogFragment {

    /** FragmentManager 的 tag（同一個編輯畫面一次只開一個）。 */
    static final String TAG = "gen-sheet";
    private static final String LOG_TAG = "SVault";

    @Nullable
    private PasswordFill fill;
    @Nullable
    private GeneratorPanel panel;

    /** 給 FragmentManager 建立實例；正式流程用 {@link #show(FragmentActivity, PasswordFill)}。 */
    public GeneratorSheet() {
        // 回填物件由 show 設定
    }

    /**
     * 在編輯畫面上開 sheet；已經開著時不再開第二個。
     *
     * @param activity 編輯畫面
     * @param fill     外殼交給模組的回填
     */
    static void show(FragmentActivity activity, PasswordFill fill) {
        FragmentManager fm = activity.getSupportFragmentManager();
        if (fm.isStateSaved() || fm.findFragmentByTag(TAG) != null) {
            return;
        }
        GeneratorSheet sheet = new GeneratorSheet();
        sheet.fill = fill;
        sheet.show(fm, TAG);
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        BottomSheetDialog dialog = (BottomSheetDialog) super.onCreateDialog(savedInstanceState);
        // 結果是密碼：不論編輯畫面怎麼設定，sheet 的視窗一律防截圖（對話框不繼承 Activity 的 FLAG_SECURE）
        SecureWindows.secure(dialog.getWindow());
        dialog.setTitle(R.string.gen_sheet_title);
        // 一打開就整個展開：內容比預設的半高多，收合的中間狀態沒有用處
        BottomSheetBehavior<?> behavior = dialog.getBehavior();
        behavior.setSkipCollapsed(true);
        behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        return dialog;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.gen_sheet, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ModuleGuard guard = new ModuleGuard((id, action, error) -> {
            // 只記類型：例外訊息不含密碼，但保守起見不寫進系統日誌
            Log.w(LOG_TAG, "產生器出錯（" + action + "）：" + error.getClass().getName());
            View anchor = panel != null ? getView() : requireActivity().findViewById(android.R.id.content);
            if (anchor != null) {
                Toasts.show(anchor, getString(R.string.gen_error), Toasts.Kind.ERROR);
            }
        });
        MaterialButton use = view.findViewById(R.id.gen_use);
        // sheet 的生命週期不在外殼的錯誤邊界裡：讀設定、第一次產生失敗時關掉 sheet，提示顯示在編輯畫面上
        GeneratorModel model = guard.call(GeneratorAndroidFeature.ID, "sheet",
                () -> new ViewModelProvider(this).get(GeneratorViewModel.class).model(requireContext())).orElse(null);
        if (model == null || fill == null) {
            dismissAllowingStateLoss();
            return;
        }
        GeneratorPanel p = new GeneratorPanel(view, model, guard, use);
        panel = p;
        // 外殼拒絕回填（已上鎖、正在儲存）時也直接關閉，不另外提示
        use.setOnClickListener(v -> p.act("use", () -> {
            if (fill != null && use(model, fill)) {
                dismiss();
            }
        }));
        view.<MaterialButton>findViewById(R.id.gen_cancel).setOnClickListener(v -> dismiss());
        p.attach();
    }

    /**
     * 「使用這個密碼」（T-44）：把目前結果的副本交給外殼回填密碼欄。外殼不論填不填都會把副本清零
     * （{@link PasswordFill#fill}），畫面上的結果不受影響。
     *
     * @param model 產生器的狀態
     * @param fill  編輯畫面交給模組的回填
     * @return 有結果交出去（sheet 可以關閉）；選項不合法或已清掉時為 {@code false}
     */
    static boolean use(GeneratorModel model, PasswordFill fill) {
        Optional<char[]> result = model.take();
        result.ifPresent(fill::fill);
        return result.isPresent();
    }

    @Override
    public void onDestroyView() {
        if (panel != null) {
            panel.detach();
            panel = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        // 不保留回填：sheet 關閉後就不能再填
        fill = null;
        super.onDestroy();
    }
}
