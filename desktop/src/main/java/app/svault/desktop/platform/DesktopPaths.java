package app.svault.desktop.platform;

import java.nio.file.Path;

/**
 * Windows 上的預設路徑。
 */
public final class DesktopPaths {

    private DesktopPaths() {
    }

    /**
     * 本機資料夾的平台預設值 {@code %APPDATA%\S-Vault}（可被 {@code SVAULT_DATA_DIR} 覆寫，見 {@code LocalStore}）。
     *
     * @return 預設根目錄；沒有 {@code APPDATA} 時（非 Windows）用使用者家目錄下的 {@code .s-vault}
     */
    public static Path defaultDataDir() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "S-Vault");
        }
        return Path.of(System.getProperty("user.home"), ".s-vault");
    }
}
