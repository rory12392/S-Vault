package app.svault.android.shell;

import android.content.Context;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;

/**
 * 探索 Android 的功能模組（{@code META-INF/services/app.svault.android.shell.ext.AndroidFeature}）。
 */
public final class AndroidModules {

    private AndroidModules() {
    }

    /**
     * @param settings 啟用狀態的來源
     * @param listener 模組出錯時的通知對象（外殼用來顯示錯誤狀態）
     * @return 探索結果；外殼之後呼叫擴充內容時沿用 {@link ModuleRegistry#guard()}
     */
    public static ModuleRegistry<AndroidFeature> discover(ModuleSettings settings, ModuleFailureListener listener) {
        return ModuleRegistry.discover(AndroidFeature.class, AndroidModules.class.getClassLoader(), settings,
                new ModuleGuard(listener));
    }

    /**
     * 停用通知（T-35）：對每個停用中的模組呼叫 {@link AndroidFeature#onDisabled}，各自在 {@code registry} 的錯誤邊界內。
     * App 啟動時在背景執行緒呼叫一次。
     *
     * @param registry 探索結果
     * @param context  App 的 Context
     */
    public static void notifyDisabled(ModuleRegistry<AndroidFeature> registry, Context context) {
        registry.forEachDisabled("onDisabled", module -> module.onDisabled(context));
    }
}
