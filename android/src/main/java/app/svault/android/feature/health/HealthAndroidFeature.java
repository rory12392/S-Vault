package app.svault.android.feature.health;

import app.svault.android.R;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import app.svault.android.shell.ext.SettingsSection;
import java.util.List;

/**
 * 健檢（HLT，US-13）的 Android 模組類別（T-53）：選單的「密碼健檢」頁（{@link HealthReportFragment}）與設定的「密碼健檢」
 * 段落（{@link HealthSettingsFragment}，上次檢查外洩的時間）。保管庫只經 {@code ModuleHost.unlockedEntries()} 唯讀取用，
 * 跳到條目用 {@code ModuleHost.navigator}。文字資源放 {@code res/values/hlt_strings.xml}。
 */
public final class HealthAndroidFeature implements AndroidFeature {

    /** 模組代號（設定鍵 {@code modules.health.*}）。 */
    public static final String ID = "health";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        // FeatureModule 是 core 的介面，這裡拿不到 Context 讀字串資源；只在模組的擴充點整個失敗時當段落標題
        return "密碼健檢";
    }

    @Override
    public List<MenuPage> menuPages() {
        return List.of(new MenuPage(R.string.hlt_page_title, HealthReportFragment.class));
    }

    @Override
    public List<SettingsSection> settingsSections() {
        return List.of(new SettingsSection(R.string.hlt_settings_title, HealthSettingsFragment.class));
    }
}
