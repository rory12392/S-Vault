package app.svault.android.feature.health;

import static org.junit.Assert.assertEquals;

import app.svault.android.R;
import app.svault.android.shell.ext.MenuPage;
import app.svault.android.shell.ext.SettingsSection;
import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.module.ModulePreferences;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.ScopedPreferences;
import app.svault.core.storage.Settings;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** android 健檢模組的註冊與設定段落（T-53）。 */
public class HealthAndroidFeatureTest {

    /** AC-13-1：選單有「密碼健檢」頁、設定有「密碼健檢」段落。 */
    @Test
    public void registersPageAndSettings() {
        HealthAndroidFeature f = new HealthAndroidFeature();
        assertEquals("health", f.id());
        assertEquals(List.of(new MenuPage(R.string.hlt_page_title, HealthReportFragment.class)), f.menuPages());
        assertEquals(List.of(new SettingsSection(R.string.hlt_settings_title, HealthSettingsFragment.class)),
                f.settingsSections());
    }

    /** T-53：設定段落顯示上次檢查外洩的時間（裝置時區、yyyy/MM/dd HH:mm）；沒有紀錄時為空（「尚未檢查」）。 */
    @Test
    public void lastBreachCheck() {
        LiveSettings live = LiveSettings.inMemory(Settings.defaults());
        ModulePreferences prefs = new ScopedPreferences(HealthAndroidFeature.ID, live::current, live::update);
        ZoneId taipei = ZoneId.of("Asia/Taipei");
        assertEquals(Optional.empty(), HealthSettingsFragment.lastBreachCheck(prefs, taipei));

        prefs.put(HealthReportModel.KEY_LAST_BREACH_CHECK, Instant.parse("2026-10-29T06:30:00Z").toEpochMilli());
        assertEquals(Optional.of("2026/10/29 14:30"), HealthSettingsFragment.lastBreachCheck(prefs, taipei));
    }
}
