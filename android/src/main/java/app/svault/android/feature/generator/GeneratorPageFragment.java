package app.svault.android.feature.generator;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.SnackbarRoom;
import app.svault.android.ui.Toasts;
import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModuleGuard;
import com.google.android.material.button.MaterialButton;
import java.util.Optional;

/**
 * 選單的「密碼產生器」（{@code MenuPage}；T-42）。外殼的 {@code FeatureHostActivity} 載入，頁首與
 * {@code FLAG_SECURE} 由外殼提供。狀態在 Activity 範圍的 {@link GeneratorViewModel}，內容由 {@link GeneratorPanel}
 * 畫出（和編輯畫面「產生」的底部 sheet 共用，T-44），這裡只加「複製」。
 *
 * <p>「複製」走 {@code ModuleHost.clipboard}（T-34：和條目畫面「複製密碼」同一個 {@code ClipboardGuard}，秒數、上鎖時清除、
 * 敏感標記都由外殼決定），提示「已複製密碼，n 秒後清除」的秒數用它的回傳值；host 沒有剪貼簿時不顯示「複製」。
 *
 * <p>每個操作都包在模組自己的 {@link ModuleGuard} 裡：產生失敗（例如字表讀不到）時顯示錯誤提示，頁面照常。
 * 產生的密碼不記錄日誌。
 */
public class GeneratorPageFragment extends Fragment {

    private static final String TAG = "SVault";

    private GeneratorModel model;
    private GeneratorPanel panel;
    private Optional<ModuleClipboard> clipboard = Optional.empty();

    /** 給 {@code FeatureHostActivity} 建立實例。 */
    public GeneratorPageFragment() {
        super(R.layout.gen_page);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        ModuleGuard guard = new ModuleGuard((id, action, error) -> {
            // 只記類型：例外訊息不含密碼，但保守起見不寫進系統日誌
            Log.w(TAG, "產生器出錯（" + action + "）：" + error.getClass().getName());
            if (getView() != null) {
                Toasts.show(getView(), getString(R.string.gen_error), Toasts.Kind.ERROR);
            }
        });
        model = new ViewModelProvider(requireActivity()).get(GeneratorViewModel.class).model(requireContext());
        clipboard = ModuleHost.of(requireContext()).clipboard(GeneratorAndroidFeature.ID);
        MaterialButton copy = view.findViewById(R.id.gen_copy);
        copy.setVisibility(clipboard.isPresent() ? View.VISIBLE : View.GONE);
        panel = new GeneratorPanel(view, model, guard, copy);
        copy.setOnClickListener(v -> panel.act("copy", this::copy));
        panel.attach();
    }

    @Override
    public void onDestroyView() {
        if (panel != null) {
            panel.detach();
        }
        super.onDestroyView();
    }

    private void copy() {
        // 頁面本身是捲動區（gen_page.xml）；最大字體時提示不要蓋住「複製」（T-109）
        ViewGroup page = (ViewGroup) requireView();
        clipboard.flatMap(model::copy).ifPresent(after -> SnackbarRoom.keepClear(Toasts.show(page,
                getString(R.string.gen_copied, after.getSeconds()), Toasts.Kind.INFO), page,
                page.findViewById(R.id.gen_copy)));
    }
}
