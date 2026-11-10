package app.svault.android.shell.settings;

import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.SettingsSection;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 設定畫面的模組段落（{@code SettingsSection}）：每個啟用中的模組的段落。
 * 整個擴充點出錯時仍列一段，標題用模組名稱、內容是「這個功能暫時無法使用」（同 desktop）。段落內容的 Fragment
 * 由 {@link SettingsFragment} 在錯誤邊界裡建立。
 */
final class ModuleSections {

    /**
     * 一個段落。
     *
     * @param moduleId      模組代號（錯誤邊界用）
     * @param titleRes      模組給的標題；整個擴充點出錯時為 0
     * @param fallbackTitle 沒有標題時用的模組名稱
     * @param page          段落內容；出錯時為空
     */
    record Section(String moduleId, @StringRes int titleRes, String fallbackTitle,
            Optional<Class<? extends Fragment>> page) { }

    private ModuleSections() {
    }

    /**
     * @param registry android 功能模組（停用的不列）
     * @return 依模組順序的段落
     */
    static List<Section> collect(ModuleRegistry<AndroidFeature> registry) {
        ModuleGuard guard = registry.guard();
        List<Section> out = new ArrayList<>();
        for (AndroidFeature module : registry.enabled()) {
            String id = guard.call(module.getClass().getName(), "id", module::id).orElse("?");
            Optional<List<SettingsSection>> sections = guard.call(id, "settingsSections",
                    () -> List.copyOf(module.settingsSections()));
            String name = guard.call(id, "displayName", module::displayName).orElse(id);
            if (!sections.isPresent()) {
                out.add(new Section(id, 0, name, Optional.empty()));
                continue;
            }
            for (SettingsSection s : sections.get()) {
                out.add(new Section(id, s.titleRes(), name, Optional.ofNullable(s.fragmentClass())));
            }
        }
        return List.copyOf(out);
    }
}
