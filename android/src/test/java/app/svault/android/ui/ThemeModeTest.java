package app.svault.android.ui;

import static org.junit.Assert.assertEquals;

import androidx.appcompat.app.AppCompatDelegate;
import org.junit.Test;

/**
 * {@code settings.json} 的 {@code theme} 值（D-19）對應到 AppCompat 的夜間模式。
 */
public class ThemeModeTest {

    @Test
    public void parsesSettingValues() {
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromSetting("light"));
        assertEquals(ThemeMode.DARK, ThemeMode.fromSetting("DARK"));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting("system"));
    }

    @Test
    public void unknownOrMissingFollowsSystem() {
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting(null));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting(""));
        assertEquals(ThemeMode.SYSTEM, ThemeMode.fromSetting("sepia"));
    }

    @Test
    public void mapsToNightModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, ThemeMode.LIGHT.nightMode());
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, ThemeMode.DARK.nightMode());
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, ThemeMode.SYSTEM.nightMode());
    }

    @Test
    public void settingValueRoundTrips() {
        for (ThemeMode mode : ThemeMode.values()) {
            assertEquals(mode, ThemeMode.fromSetting(mode.settingValue()));
        }
    }
}
