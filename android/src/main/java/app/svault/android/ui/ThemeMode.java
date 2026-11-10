package app.svault.android.ui;

import androidx.appcompat.app.AppCompatDelegate;
import java.util.Locale;

/**
 * 主題設定（{@code settings.json} 的 {@code theme}，D-19）：淺色、深色、跟隨系統（預設）。
 */
public enum ThemeMode {
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);

    private final String settingValue;
    @AppCompatDelegate.NightMode private final int nightMode;

    ThemeMode(String settingValue, @AppCompatDelegate.NightMode int nightMode) {
        this.settingValue = settingValue;
        this.nightMode = nightMode;
    }

    /**
     * @param value 設定值；不認得或沒有設定時視為跟隨系統
     * @return 對應的模式
     */
    public static ThemeMode fromSetting(String value) {
        if (value != null) {
            String v = value.trim().toLowerCase(Locale.ROOT);
            for (ThemeMode mode : values()) {
                if (mode.settingValue.equals(v)) {
                    return mode;
                }
            }
        }
        return SYSTEM;
    }

    /** @return 寫回 {@code settings.json} 的值 */
    public String settingValue() {
        return settingValue;
    }

    /** @return 給 {@link AppCompatDelegate#setDefaultNightMode(int)} 的值 */
    @AppCompatDelegate.NightMode
    public int nightMode() {
        return nightMode;
    }
}
