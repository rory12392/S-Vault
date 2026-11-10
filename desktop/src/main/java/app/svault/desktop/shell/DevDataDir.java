package app.svault.desktop.shell;

/**
 * 開發版不碰使用者真實的本機資料夾：{@code svault.dev=true}（{@code :desktop:run}）而沒有指定 {@code SVAULT_DATA_DIR}
 * 時拒絕啟動，否則開發中的程式會讀寫 {@code %APPDATA%\S-Vault} 裡真正的最近開啟清單、設定、備份與暫存。
 * 打包版不是開發模式，照常使用預設資料夾。
 */
final class DevDataDir {

    /** 拒絕啟動時印出的說明（只印到標準錯誤，不含任何路徑以外的資訊）。 */
    static final String MESSAGE = "開發模式必須指定 SVAULT_DATA_DIR（例：$env:SVAULT_DATA_DIR = \"$PWD\\.localdata\\data\"），"
            + "以免讀寫真正的 %APPDATA%\\S-Vault。";

    private DevDataDir() {
    }

    /**
     * @param devMode        {@code svault.dev} 是否為 {@code true}
     * @param systemProperty 系統屬性 {@code SVAULT_DATA_DIR}；沒有時 {@code null}
     * @param environment    環境變數 {@code SVAULT_DATA_DIR}；沒有時 {@code null}
     * @return 要拒絕啟動
     */
    static boolean mustRefuse(boolean devMode, String systemProperty, String environment) {
        return devMode && isBlank(systemProperty) && isBlank(environment);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
