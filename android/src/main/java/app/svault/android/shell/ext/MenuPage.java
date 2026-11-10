package app.svault.android.shell.ext;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

/**
 * 選單中的獨立頁面，由外殼的 {@code FeatureHostActivity}（T-03）以 Fragment 類別載入。
 *
 * @param titleRes      頁面標題的字串資源
 * @param fragmentClass 頁面的 Fragment（需有 public 無參數建構子）
 */
public record MenuPage(@StringRes int titleRes, Class<? extends Fragment> fragmentClass) { }
