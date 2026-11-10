package app.svault.desktop.shell.start;

import app.svault.core.shell.Texts;
import app.svault.core.storage.RecentVault;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 歡迎畫面最近開啟清單的一列（資料來自 {@code recent.json}，AC-06-3）。
 *
 * @param file       保管庫檔案（{@code RecentVault.location()} 是 {@code FileVaultLocation} 的完整路徑）
 * @param name       顯示名稱（檔名去掉 {@code .kdbx}）
 * @param lastOpened 最後開啟時間（裝置時區、{@code yyyy/MM/dd HH:mm}）
 * @param location   {@code recent.json} 裡的位置字串（從清單移除時用）
 */
record RecentItem(Path file, String name, String lastOpened, String location) {

    private static final DateTimeFormatter TIME = Texts.MINUTES;

    /**
     * @param vault 最近開啟的紀錄
     * @param zone  顯示時間用的時區
     * @return 清單列
     */
    static RecentItem of(RecentVault vault, ZoneId zone) {
        Path file = Paths.get(vault.location());
        return new RecentItem(file, displayName(file), TIME.format(vault.lastOpened().atZone(zone)),
                vault.location());
    }

    /**
     * @param file 保管庫檔案
     * @return 檔名去掉 {@code .kdbx}
     */
    static String displayName(Path file) {
        String fileName = file.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(CreateForm.EXTENSION)) {
            return fileName.substring(0, fileName.length() - CreateForm.EXTENSION.length());
        }
        return fileName;
    }
}
