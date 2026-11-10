package app.svault.desktop.feature.importer;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.ResourceBundle;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * 選 CSV 檔（第 1 步的「選擇 CSV 檔」）。正式程式用系統的開檔對話框；外殼的畫面腳本換不掉模組自己的對話框
 * （{@code pick=} 只替換外殼的 {@code FileDialogs}），開發版另有固定路徑的版本（{@link #fixed}）。
 */
@FunctionalInterface
interface CsvPicker {

    /** 開發版畫面腳本用的環境變數：設了就不開對話框，直接用這個路徑（只在 {@code svault.dev=true} 時看）。 */
    String DEV_ENV = "SVAULT_DEV_IMPORT_CSV";

    /**
     * @param owner 對話框的上層視窗
     * @return 選的檔案；取消時為空
     */
    Optional<Path> pick(Window owner);

    /**
     * @param text 模組的文字
     * @return 系統的開檔對話框（篩選 {@code *.csv}，也可以選所有檔案）
     */
    static CsvPicker system(ResourceBundle text) {
        return owner -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(text.getString("choose.dialog.title"));
            chooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter(text.getString("choose.dialog.filter"), "*.csv"),
                    new FileChooser.ExtensionFilter(text.getString("choose.dialog.all"), "*.*"));
            File picked = chooser.showOpenDialog(owner);
            return Optional.ofNullable(picked).map(File::toPath);
        };
    }

    /**
     * @param path 每次都回傳的路徑
     * @return 不開對話框的版本（開發版畫面腳本）
     */
    static CsvPicker fixed(Path path) {
        return owner -> Optional.of(path);
    }
}
