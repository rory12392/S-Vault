package app.svault.desktop.shell.start;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * 選檔對話框。正式程式用 {@link #system}（Windows 原生的開啟／另存新檔）；開發版的畫面腳本換成預先指定結果的版本，
 * 截圖與鍵盤檢查時不用操作原生對話框。
 */
public interface FileDialogs {

    /**
     * 開啟既有的保管庫。
     *
     * @param owner      擁有者視窗
     * @param initialDir 起始資料夾；沒有時 {@code null}
     * @return 選到的檔案；取消時空值
     */
    Optional<Path> open(Window owner, Path initialDir);

    /**
     * 選擇新保管庫的存放位置。
     *
     * @param owner         擁有者視窗
     * @param initialDir    起始資料夾；沒有時 {@code null}
     * @param suggestedName 預設檔名（含 {@code .kdbx}）
     * @return 選到的位置；取消時空值
     */
    Optional<Path> save(Window owner, Path initialDir, String suggestedName);

    /** @return Windows 原生對話框 */
    static FileDialogs system() {
        ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.start.messages");
        return new FileDialogs() {
            @Override
            public Optional<Path> open(Window owner, Path initialDir) {
                FileChooser chooser = chooser(text.getString("open.dialog.title"), initialDir);
                return Optional.ofNullable(chooser.showOpenDialog(owner)).map(File::toPath);
            }

            @Override
            public Optional<Path> save(Window owner, Path initialDir, String suggestedName) {
                FileChooser chooser = chooser(text.getString("create.dialog.title"), initialDir);
                chooser.setInitialFileName(suggestedName);
                return Optional.ofNullable(chooser.showSaveDialog(owner)).map(File::toPath);
            }

            private FileChooser chooser(String title, Path initialDir) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle(title);
                chooser.getExtensionFilters().add(
                        new FileChooser.ExtensionFilter(text.getString("open.dialog.filter"), "*.kdbx"));
                if (initialDir != null && Files.isDirectory(initialDir)) {
                    chooser.setInitialDirectory(initialDir.toFile());
                }
                return chooser;
            }
        };
    }
}
