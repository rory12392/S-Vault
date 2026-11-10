package app.svault.android.shell.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.svault.android.R;
import app.svault.core.storage.Settings;
import java.util.List;
import org.junit.Test;

/**
 * 設定畫面的選項：背景上鎖與剪貼簿秒數的選項都在 core 的範圍內（D-122），含預設值；
 * 檔案裡是清單以外的合法值時也列出來，不會被畫面改掉。
 */
public class SettingsChoicesTest {

    @Test
    public void ac12_2_backgroundLockOptionsAreInRangeAndIncludeDefault() {
        List<Integer> options = SettingsChoices.backgroundLock(Settings.AUTO_LOCK_ANDROID_SECONDS.defaultValue());
        assertEquals(List.of(0, 15, 30, 60, 120, 300, 600, 1800, 3600), options);
        for (int s : options) {
            assertTrue(s + " 秒不在範圍內", Settings.AUTO_LOCK_ANDROID_SECONDS.accepts(s));
        }
        assertEquals(30, Settings.defaults().autoLockAndroidSeconds());
    }

    @Test
    public void ac05_clipboardOptionsAreInRangeAndIncludeDefault() {
        List<Integer> options = SettingsChoices.clipboard(Settings.CLIPBOARD_CLEAR_SECONDS.defaultValue());
        assertEquals(List.of(10, 15, 30, 45, 60, 90, 120, 180, 300), options);
        for (int s : options) {
            assertTrue(s + " 秒不在範圍內", Settings.CLIPBOARD_CLEAR_SECONDS.accepts(s));
        }
    }

    @Test
    public void storedValueOutsideListIsKeptAsOption() {
        assertEquals(List.of(0, 15, 30, 45, 60, 120, 300, 600, 1800, 3600), SettingsChoices.backgroundLock(45));
        assertEquals(List.of(10, 15, 30, 45, 60, 90, 100, 120, 180, 300), SettingsChoices.clipboard(100));
        // 超出範圍的值 Settings 讀的時候已換成預設值，這裡不會收到；收到也不列
        assertEquals(9, SettingsChoices.clipboard(5).size());
    }

    @Test
    public void durationLabels() {
        assertEquals(new SettingsChoices.Label(R.string.settings_immediately, List.of()),
                SettingsChoices.duration(0));
        assertEquals(new SettingsChoices.Label(R.string.settings_seconds, List.of(15)), SettingsChoices.duration(15));
        assertEquals(new SettingsChoices.Label(R.string.settings_minutes, List.of(1)), SettingsChoices.duration(60));
        assertEquals(new SettingsChoices.Label(R.string.settings_minutes, List.of(30)),
                SettingsChoices.duration(1800));
        assertEquals(new SettingsChoices.Label(R.string.settings_hours, List.of(1)), SettingsChoices.duration(3600));
        assertEquals(new SettingsChoices.Label(R.string.settings_minutes_seconds, List.of(1, 30)),
                SettingsChoices.duration(90));
    }

    @Test
    public void themeChoices() {
        assertEquals(List.of(Settings.THEME_LIGHT, Settings.THEME_DARK, Settings.THEME_SYSTEM), SettingsChoices.THEMES);
        assertEquals(R.string.settings_theme_light, SettingsChoices.themeLabel(Settings.THEME_LIGHT));
        assertEquals(R.string.settings_theme_dark, SettingsChoices.themeLabel(Settings.THEME_DARK));
        assertEquals(R.string.settings_theme_system, SettingsChoices.themeLabel(Settings.THEME_SYSTEM));
    }
}
