package app.svault.android.shell;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentFactory;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

/**
 * 重建畫面（旋轉、換主題）時不讓 FragmentManager 自己還原功能模組的 Fragment（T-22）：還原發生在
 * {@code super.onCreate} 裡，模組的 {@code onCreate}／{@code onCreateView} 丟例外就會讓 App 結束，不在錯誤邊界內。
 * 還原期間凡不是外殼的類別都換成 {@link PendingModuleFragment}（沒有畫面），之後由外殼在 {@code onStart} 以
 * {@code ModuleGuard} 重新建立（{@link FeatureHostActivity}、{@code SettingsFragment}），或拿掉（編輯畫面上模組開的
 * sheet，{@link #removePlaceholders}，T-37）。
 *
 * <p>代價：模組頁面自己的 {@code onSaveInstanceState} 內容在重建時不會還給新的實例（參數會）；要跨重建保留的狀態放
 * Activity 範圍的 {@code ViewModel}（Architecture §1.1）。模組開的 {@code DialogFragment} 在重建後也會消失。
 */
public final class ModuleRestoreFactory extends FragmentFactory {

    private boolean restoring;

    /**
     * @param on {@code true}：之後建立的非外殼 Fragment 一律換成 {@link PendingModuleFragment}
     */
    public void restoring(boolean on) {
        restoring = on;
    }

    @NonNull
    @Override
    public Fragment instantiate(@NonNull ClassLoader classLoader, @NonNull String className) {
        if (restoring && !isShell(className)) {
            return new PendingModuleFragment();
        }
        return super.instantiate(classLoader, className);
    }

    /**
     * 拿掉重建時留下的佔位（不會再換回模組 Fragment 的畫面用，例如編輯畫面上模組開的 sheet）。在 {@code onStart} 呼叫。
     *
     * @param fm 畫面的 FragmentManager
     */
    public static void removePlaceholders(FragmentManager fm) {
        FragmentTransaction remove = null;
        for (Fragment f : fm.getFragments()) {
            if (f instanceof PendingModuleFragment) {
                remove = remove == null ? fm.beginTransaction() : remove;
                remove.remove(f);
            }
        }
        if (remove != null) {
            remove.commitNowAllowingStateLoss();
        }
    }

    /**
     * @param className Fragment 的完整類別名稱
     * @return 外殼自己的頁面（設定、關於）與共用元件，照常還原
     */
    static boolean isShell(String className) {
        return className.startsWith("app.svault.android.shell.") || className.startsWith("app.svault.android.ui.");
    }
}
