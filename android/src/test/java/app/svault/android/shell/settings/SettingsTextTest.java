package app.svault.android.shell.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 設定與關於頁的文字：兩平台共用的文字和 desktop 的 {@code messages.properties} 一致；
 * 只有 android 的（背景上鎖、選項的時間）照 T-24 定的文字。
 */
public class SettingsTextTest {

    private static final Map<String, String> TEXT = load();
    private static final Properties DESKTOP = desktop();

    @Test
    public void sharedTextsMatchDesktop() {
        String[][] same = {
            {"settings_title", "settings.title"},
            {"settings_section_appearance", "settings.section.appearance"},
            {"settings_theme", "settings.theme"},
            {"settings_theme_light", "settings.theme.light"},
            {"settings_theme_dark", "settings.theme.dark"},
            {"settings_theme_system", "settings.theme.system"},
            {"settings_section_security", "settings.section.security"},
            {"settings_clipboard", "settings.clipboard"},
            {"settings_section_backup", "settings.section.backup"},
            {"settings_backup", "settings.backup"},
            {"settings_backup_description", "settings.backup.description"},
            {"settings_section_about", "settings.section.about"},
            {"settings_about", "settings.about"},
            {"settings_module_error", "settings.module.error"},
            {"settings_section_modules", "settings.section.modules"},
            {"settings_modules_disable_confirm", "settings.modules.disable.confirm"},
            {"settings_save_failed", "settings.saveFailed"},
            {"about_title", "about.title"},
            {"about_tagline", "about.tagline"},
            {"about_licenses", "about.licenses"},
            {"about_licenses_description", "about.licenses.description"},
        };
        for (String[] pair : same) {
            assertNotNull(pair[0], TEXT.get(pair[0]));
            assertEquals(pair[0], DESKTOP.getProperty(pair[1]), TEXT.get(pair[0]));
        }
        // desktop 用 MessageFormat 的 {0}，android 用 %1$s
        assertEquals(DESKTOP.getProperty("about.version").replace("{0}", "%1$s"), TEXT.get("about_version"));
        assertEquals(DESKTOP.getProperty("about.item.license").replace("{0}", "%1$s"), TEXT.get("about_item_license"));
        assertEquals(DESKTOP.getProperty("about.item.purpose").replace("{0}", "%1$s"), TEXT.get("about_item_purpose"));
        assertEquals(DESKTOP.getProperty("about.licenseText").replace("{0}", "%1$s"), TEXT.get("about_license_text"));
        assertEquals(DESKTOP.getProperty("settings.modules.disable.title").replace("{0}", "%1$s"),
                TEXT.get("settings_modules_disable_title"));
    }

    /** T-38：「功能模組」的重新啟動提示與停用確認（android 要完全關閉 App，說法和 desktop 不同）。 */
    @Test
    public void moduleToggleTexts() {
        assertEquals("完全關閉 S-Vault 再開啟後啟用", TEXT.get("settings_modules_restart_on"));
        assertEquals("完全關閉 S-Vault 再開啟後停用", TEXT.get("settings_modules_restart_off"));
        String message = TEXT.get("settings_modules_disable_message");
        assertTrue(message, message.contains("最近使用的應用程式") && message.contains("生物辨識")
                && message.contains("重新開啟"));
    }

    @Test
    public void ac12_2_androidOnlyTexts() {
        assertEquals("設定", TEXT.get("settings_menu"));
        assertEquals("關於", TEXT.get("about_menu"));
        assertEquals("淺色、深色，或跟著手機的設定", TEXT.get("settings_theme_description"));
        assertEquals("切到背景後自動上鎖", TEXT.get("settings_auto_lock"));
        assertEquals("螢幕關閉時一律立即上鎖", TEXT.get("settings_auto_lock_description"));
        assertEquals("複製密碼後，經過這段時間清除剪貼簿", TEXT.get("settings_clipboard_description"));
        assertEquals("版本與開源授權", TEXT.get("settings_about_description"));
        assertEquals("立即", TEXT.get("settings_immediately"));
        assertEquals("%1$d 秒", TEXT.get("settings_seconds"));
        assertEquals("%1$d 分鐘", TEXT.get("settings_minutes"));
        assertEquals("%1$d 分 %2$d 秒", TEXT.get("settings_minutes_seconds"));
        assertEquals("%1$d 小時", TEXT.get("settings_hours"));
    }

    private static Map<String, String> load() {
        try {
            String xml = new String(Files.readAllBytes(Paths.get("src", "main", "res", "values", "strings.xml")),
                    StandardCharsets.UTF_8);
            Map<String, String> out = new HashMap<>();
            Matcher m = Pattern.compile("<string name=\"([a-z0-9_]+)\"[^>]*>(.*?)</string>").matcher(xml);
            while (m.find()) {
                out.put(m.group(1), m.group(2).replace("\\'", "'"));
            }
            return out;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Properties desktop() {
        // android 子專案的工作目錄是 android/；desktop 的文字只讀不改
        Path file = Paths.get("..", "desktop", "src", "main", "resources", "app", "svault", "desktop", "shell",
                "settings", "messages.properties");
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }
}
