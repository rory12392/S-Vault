package app.svault.android.shell.start;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.R;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.UnlockProvider;
import app.svault.core.module.Contribution;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.shell.Secrets;
import com.google.android.material.button.MaterialButton;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 解鎖畫面上功能模組的替代解鎖方式（{@link UnlockProvider}，例如 BIO 的「使用生物辨識解鎖」）。
 * 每次呼叫模組都包在 {@link ModuleGuard} 裡：模組停用、出錯或這個保管庫沒有設定時不顯示按鈕。
 */
final class UnlockProviderButtons {

    private final FragmentActivity activity;
    private final LinearLayout container;
    private final ModuleRegistry<AndroidFeature> modules;

    /**
     * @param activity  解鎖畫面
     * @param container 放按鈕的區塊
     * @param modules   功能模組
     */
    UnlockProviderButtons(FragmentActivity activity, LinearLayout container, ModuleRegistry<AndroidFeature> modules) {
        this.activity = activity;
        this.container = container;
        this.modules = modules;
    }

    /**
     * @param vaultId    保管庫 ID；不知道時為 {@code null}（不顯示任何按鈕）
     * @param onPassword 模組交回主密碼時呼叫（收到的陣列由它負責清零）
     * @param onFailure  模組失敗時呼叫（AC-11-4：退回主密碼輸入）
     */
    void show(@Nullable String vaultId, Consumer<byte[]> onPassword, Runnable onFailure) {
        container.removeAllViews();
        if (vaultId != null) {
            ModuleGuard guard = modules.guard();
            List<Contribution<UnlockProvider>> found = modules.collect("unlockProviders",
                    AndroidFeature::unlockProviders);
            for (Contribution<UnlockProvider> c : found) {
                addButton(guard, c.moduleId(), c.value(), vaultId, onPassword, onFailure);
            }
        }
        container.setVisibility(container.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    /** @param enabled 解鎖中停用 */
    void setEnabled(boolean enabled) {
        for (int i = 0; i < container.getChildCount(); i++) {
            container.getChildAt(i).setEnabled(enabled);
        }
    }

    private void addButton(ModuleGuard guard, String moduleId, UnlockProvider provider, String vaultId,
            Consumer<byte[]> onPassword, Runnable onFailure) {
        Optional<Boolean> available = guard.call(moduleId, "isAvailable", () -> provider.isAvailable(vaultId));
        Optional<Integer> label = guard.call(moduleId, "labelRes", provider::labelRes);
        if (!available.orElse(false) || !label.isPresent()) {
            return;
        }
        MaterialButton button = (MaterialButton) LayoutInflater.from(activity)
                .inflate(R.layout.unlock_provider_button, container, false);
        button.setText(label.get());
        button.setOnClickListener(v -> guard.run(moduleId, "start",
                () -> provider.start(activity, vaultId, new UnlockProvider.Callback() {
                    @Override
                    public void onSuccess(byte[] masterPassword) {
                        // 外殼負責清零（UnlockProvider 的約定）：複製一份交出去，原陣列立刻清掉
                        byte[] copy = masterPassword.clone();
                        Secrets.wipe(masterPassword);
                        onPassword.accept(copy);
                    }

                    @Override
                    public void onCancel() {
                    }

                    @Override
                    public void onFailure(Throwable error) {
                        // 原因由模組自己顯示
                        onFailure.run();
                        // 失敗可能讓這個方式不能再用（金鑰失效，AC-11-3）：重新問一次，不能用的按鈕立刻消失（P-BIO-04）
                        show(vaultId, onPassword, onFailure);
                    }
                })));
        container.addView(button);
    }
}
