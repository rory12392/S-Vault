package app.svault.android.shell;

import androidx.fragment.app.Fragment;

/**
 * 重建畫面時佔住功能模組 Fragment 位置的空白 Fragment（{@link ModuleRestoreFactory}）。沒有畫面、不做任何事；
 * 外殼在 {@code onStart} 以錯誤邊界重新建立模組的 Fragment 換掉它。
 */
public final class PendingModuleFragment extends Fragment {

    /** FragmentManager 再次重建時以無參數建構子建立。 */
    public PendingModuleFragment() {
        super();
    }
}
