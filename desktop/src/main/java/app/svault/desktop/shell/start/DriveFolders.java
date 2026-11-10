package app.svault.desktop.shell.start;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 找 Google Drive 電腦版的同步資料夾，當作建立保管庫時另存新檔對話框的預設位置。
 * Drive 電腦版把雲端硬碟掛成一個磁碟機（預設 {@code G:}，被占用時往後找），根目錄底下是「我的雲端硬碟」
 * （英文介面是 {@code My Drive}）。只檢查資料夾是否存在，不讀任何內容。
 */
final class DriveFolders {

    private static final List<String> NAMES = List.of("我的雲端硬碟", "My Drive");

    private DriveFolders() {
    }

    /**
     * @param roots 要找的磁碟機根目錄（正式程式用 {@code FileSystems.getDefault().getRootDirectories()}）
     * @return 第一個找到的同步資料夾；都沒有時空值
     */
    static Optional<Path> find(Iterable<Path> roots) {
        for (Path root : roots) {
            for (String name : NAMES) {
                Path candidate = root.resolve(name);
                if (Files.isDirectory(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
