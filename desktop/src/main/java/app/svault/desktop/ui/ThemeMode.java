package app.svault.desktop.ui;

import java.util.Locale;
import javafx.application.ColorScheme;

/**
 * 主題設定（{@code settings.json} 的 {@code theme}：{@code light}／{@code dark}／{@code system}，D-19）。
 */
public enum ThemeMode {
    LIGHT, DARK, SYSTEM;

    /**
     * @param system 系統目前的色彩配置（{@code Platform.Preferences}）；取不到時傳 {@code null}
     * @return 實際要套用的配置；跟隨系統但取不到系統值時用淺色
     */
    public ColorScheme resolve(ColorScheme system) {
        return switch (this) {
            case LIGHT -> ColorScheme.LIGHT;
            case DARK -> ColorScheme.DARK;
            case SYSTEM -> system == ColorScheme.DARK ? ColorScheme.DARK : ColorScheme.LIGHT;
        };
    }

    /** @return 設定檔中的值 */
    public String settingValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * @param value 設定檔中的值
     * @return 對應的模式；不認得的值（含 {@code null}）視為跟隨系統
     */
    public static ThemeMode fromSetting(String value) {
        if (value != null) {
            for (ThemeMode mode : values()) {
                if (mode.settingValue().equals(value.trim())) {
                    return mode;
                }
            }
        }
        return SYSTEM;
    }
}
