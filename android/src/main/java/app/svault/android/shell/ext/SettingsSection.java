package app.svault.android.shell.ext;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

/**
 * 設定畫面中的模組段落。內容是一般的 Fragment，用共用元件（{@code ui}）與外殼設定列同樣的版面，不用 AndroidX Preference
 * （T-22 決定，D-128 的延伸）；讀寫自己的設定用 {@code ModuleHost.of(requireContext()).preferences(模組代號)}，
 * 改了就生效、沒有「儲存」按鈕。
 *
 * @param titleRes      段落標題的字串資源
 * @param fragmentClass 段落內容的 Fragment（public、有 public 無參數建構子）
 */
public record SettingsSection(@StringRes int titleRes, Class<? extends Fragment> fragmentClass) { }
