package app.svault.desktop.shell.settings;

import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.SettingsSection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javafx.scene.Node;

/**
 * 設定畫面的模組段落（{@code SettingsSection}）：每個啟用中的模組的段落，
 * 建立內容時包在 {@link ModuleGuard} 裡。出錯的段落仍顯示標題，內容改成「這個功能暫時無法使用」。
 */
final class ModuleSections {

    /**
     * 一個段落。
     *
     * @param title   段落標題（模組給的標題；整個擴充點都失敗時用模組名稱）
     * @param content 內容；出錯時為空
     */
    record Section(String title, Optional<Node> content) { }

    private ModuleSections() {
    }

    /**
     * 在 JavaFX 執行緒上呼叫（會建立模組的畫面元件）。
     *
     * @param registry desktop 功能模組（停用的不列）
     * @return 依模組順序的段落
     */
    static List<Section> build(ModuleRegistry<DesktopFeature> registry) {
        ModuleGuard guard = registry.guard();
        List<Section> out = new ArrayList<>();
        for (DesktopFeature module : registry.enabled()) {
            String id = guard.call(module.getClass().getName(), "id", module::id).orElse("");
            Optional<List<SettingsSection>> sections = guard.call(id, "settingsSections",
                    () -> List.copyOf(module.settingsSections()));
            if (sections.isEmpty()) {
                String name = guard.call(id, "displayName", module::displayName).orElse(id);
                out.add(new Section(name, Optional.empty()));
                continue;
            }
            for (SettingsSection s : sections.get()) {
                out.add(new Section(s.title(), guard.call(id, "settingsSection", () -> s.factory().get())));
            }
        }
        return List.copyOf(out);
    }
}
