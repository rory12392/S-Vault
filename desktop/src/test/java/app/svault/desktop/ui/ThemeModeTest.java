package app.svault.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.application.ColorScheme;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ThemeModeTest {

    @Test
    @DisplayName("淺色／深色固定，跟隨系統時依系統值；系統值取不到時用淺色（D-19）")
    void resolve() {
        assertEquals(ColorScheme.LIGHT, ThemeMode.LIGHT.resolve(ColorScheme.DARK));
        assertEquals(ColorScheme.DARK, ThemeMode.DARK.resolve(ColorScheme.LIGHT));
        assertEquals(ColorScheme.DARK, ThemeMode.SYSTEM.resolve(ColorScheme.DARK));
        assertEquals(ColorScheme.LIGHT, ThemeMode.SYSTEM.resolve(ColorScheme.LIGHT));
        assertEquals(ColorScheme.LIGHT, ThemeMode.SYSTEM.resolve(null));
    }

    @Test
    @DisplayName("settings.json 的 theme 值：light／dark／system，不認得的值視為跟隨系統")
    void settingValues() {
        for (ThemeMode m : ThemeMode.values()) {
            assertEquals(m, ThemeMode.fromSetting(m.settingValue()));
        }
        assertEquals("system", ThemeMode.SYSTEM.settingValue());
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting(null));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting("DARK-ish"));
        assertEquals(ThemeMode.DARK, ThemeMode.fromSetting(" dark "));
    }

    @Test
    @DisplayName("主題樣式表依配置選淺色或深色")
    void themeSheet() {
        assertTrue(ThemeManager.themeSheet(ColorScheme.DARK).endsWith("/theme-dark.css"));
        assertTrue(ThemeManager.themeSheet(ColorScheme.LIGHT).endsWith("/theme-light.css"));
    }
}
