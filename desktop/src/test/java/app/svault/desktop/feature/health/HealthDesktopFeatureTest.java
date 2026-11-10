package app.svault.desktop.feature.health;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.module.ModulePreferences;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.ScopedPreferences;
import app.svault.core.storage.Settings;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.SettingsSection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 健檢模組的註冊（T-52）：選單頁「密碼健檢」、設定段落與上次檢查時間的顯示。頁面內容在 JavaFX 上，
 * 由畫面腳本與截圖驗證（dev-log）；邏輯在 core 的 {@code HealthReportModelTest}（T-117）。
 */
class HealthDesktopFeatureTest {

    private final LiveSettings live = LiveSettings.inMemory(Settings.defaults());
    private final ModulePreferences prefs = new ScopedPreferences(HealthDesktopFeature.ID, live::current,
            live::update);

    @Test
    @DisplayName("AC-13-1：註冊一個 MenuPage「密碼健檢」與一個設定段落「密碼健檢」")
    void registersPageAndSettings() {
        HealthDesktopFeature f = new HealthDesktopFeature();
        assertEquals("health", f.id());
        assertEquals("密碼健檢", f.displayName());
        List<MenuPage> pages = f.menuPages();
        assertEquals(1, pages.size());
        assertEquals("密碼健檢", pages.get(0).title());
        List<SettingsSection> sections = f.settingsSections();
        assertEquals(1, sections.size());
        assertEquals("密碼健檢", sections.get(0).title());
    }

    @Test
    @DisplayName("T-52：設定段落顯示上次檢查外洩的時間（裝置時區），沒有紀錄時「尚未檢查」")
    void lastBreachCheck() {
        HealthDesktopFeature f = new HealthDesktopFeature(
                Clock.fixed(Instant.parse("2026-10-22T00:00:00Z"), ZoneId.of("Asia/Taipei")));
        assertEquals("尚未檢查", f.lastBreachCheck(prefs));
        prefs.put(HealthReportModel.KEY_LAST_BREACH_CHECK, Instant.parse("2026-10-22T06:30:00Z").toEpochMilli());
        assertEquals("2026/10/22 14:30", f.lastBreachCheck(prefs));
    }
}
