package app.svault.desktop.shell.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.module.ModuleToggle;
import app.svault.core.shell.Notices;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.SettingsSection;
import app.svault.desktop.ui.ThemeMode;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import javafx.scene.Group;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 設定與關於的固定文字（測試逐字比對）與畫面背後的邏輯。
 */
class SettingsTextTest {

    private static final ResourceBundle TEXT =
            ResourceBundle.getBundle("app.svault.desktop.shell.settings.messages");

    @Test
    @DisplayName("主題三個選項：淺色／深色／跟隨系統（PRD §4）")
    void themeLabels() {
        List<String> labels = new ArrayList<>();
        for (ThemeMode m : ThemeMode.values()) {
            labels.add(TEXT.getString("settings.theme." + m.settingValue()));
        }
        assertEquals(List.of("淺色", "深色", "跟隨系統"), labels);
    }

    @Test
    @DisplayName("固定文字：模組段落出錯、關於頁的一行說明")
    void fixedTexts() {
        assertEquals("這個功能暫時無法使用", TEXT.getString("settings.module.error"));
        assertEquals("免費、資料由你掌控的密碼管理器", TEXT.getString("about.tagline"));
        assertEquals("設定", TEXT.getString("settings.title"));
    }

    @Test
    @DisplayName("T-38：「功能模組」區段——改了開關要重新啟動才生效的提示，停用前的確認")
    void moduleToggleTexts() {
        assertEquals("功能模組", TEXT.getString("settings.section.modules"));
        assertEquals(null, ModuleToggleRows.note(new ModuleToggle("health", "健檢", true, true), TEXT));
        assertEquals(null, ModuleToggleRows.note(new ModuleToggle("health", "健檢", false, false), TEXT));
        assertEquals("重新啟動 S-Vault 後停用",
                ModuleToggleRows.note(new ModuleToggle("health", "健檢", true, false), TEXT));
        assertEquals("重新啟動 S-Vault 後啟用",
                ModuleToggleRows.note(new ModuleToggle("health", "健檢", false, true), TEXT));
        assertEquals("停用「健檢」？",
                MessageFormat.format(TEXT.getString("settings.modules.disable.title"), "健檢"));
        assertTrue(TEXT.getString("settings.modules.disable.body").contains("重新開啟"));
        assertEquals("停用", TEXT.getString("settings.modules.disable.confirm"));
    }

    @Test
    @DisplayName("關於頁的版本：打包版讀 jpackage 的版本，開發執行時顯示「開發版」")
    void version() {
        assertEquals("版本 0.1.0", AboutInfo.version("0.1.0", TEXT));
        assertEquals("版本 開發版", AboutInfo.version(null, TEXT));
        assertEquals("版本 開發版", AboutInfo.version(" ", TEXT));
    }

    @Test
    @DisplayName("關於頁的開源元件：說明行與授權全文（兩種授權接在一起）")
    void licenseDetails() {
        Notices.Item jsr = new Notices.Item("JSR 305", "3.0.2", "Apache License 2.0（JSR 305 為 BSD 3-Clause）",
                "註記用", List.of("apache-2.0", "bsd-3-clause"));
        assertEquals(List.of("版本 3.0.2", "授權：Apache License 2.0（JSR 305 為 BSD 3-Clause）", "用途：註記用"),
                AboutInfo.details(jsr, TEXT));
        String full = AboutInfo.licenseText(jsr);
        assertTrue(full.contains("Apache License"));
        assertTrue(full.contains("BSD 3-Clause"));

        Notices.Item icon = new Notices.Item("Material Symbols", "", "Apache License 2.0", "",
                List.of("apache-2.0"));
        assertEquals(List.of("授權：Apache License 2.0"), AboutInfo.details(icon, TEXT));
    }

    @Test
    @DisplayName("數字欄的輸入：不是數字時保留原值、超出範圍取邊界、全形數字也可以")
    void spinnerInput() {
        assertEquals(15, SpinnerInput.parse(" 15 ", 5, 1, 60));
        assertEquals(5, SpinnerInput.parse("abc", 5, 1, 60));
        assertEquals(60, SpinnerInput.parse("999", 5, 1, 60));
        assertEquals(1, SpinnerInput.parse("0", 5, 1, 60));
        assertEquals(20, SpinnerInput.parse("２０", 5, 1, 60));
    }

    @Test
    @DisplayName("模組段落：正常的有內容；擴充點或段落丟例外時仍列出標題、內容是錯誤狀態，並回報；停用的不列")
    void moduleSections() {
        List<String> failures = new ArrayList<>();
        ModuleGuard guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
        List<DesktopFeature> modules = List.of(
                module("good", "Good", true, () -> List.of(new SettingsSection("好的段落", Group::new))),
                module("broken", "Broken", true, () -> {
                    throw new IllegalStateException("擴充點失敗");
                }),
                module("factory", "Factory", true, () -> List.of(new SettingsSection("建立失敗的段落", () -> {
                    throw new IllegalStateException("段落失敗");
                }))),
                module("off", "Off", false, () -> List.of(new SettingsSection("停用的段落", Group::new))));
        ModuleRegistry<DesktopFeature> registry = ModuleRegistry.of(modules, ModuleSettings.DEFAULTS, guard);

        List<ModuleSections.Section> sections = ModuleSections.build(registry);

        assertEquals(List.of("好的段落", "Broken", "建立失敗的段落"),
                sections.stream().map(ModuleSections.Section::title).toList());
        assertTrue(sections.get(0).content().isPresent());
        assertTrue(sections.get(1).content().isEmpty());
        assertTrue(sections.get(2).content().isEmpty());
        assertEquals(List.of("broken:settingsSections", "factory:settingsSection"), failures);
    }

    private interface SectionSource {
        List<SettingsSection> get();
    }

    private static DesktopFeature module(String id, String name, boolean enabled, SectionSource sections) {
        return new DesktopFeature() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return name;
            }

            @Override
            public boolean enabledByDefault() {
                return enabled;
            }

            @Override
            public List<SettingsSection> settingsSections() {
                return sections.get();
            }
        };
    }
}
