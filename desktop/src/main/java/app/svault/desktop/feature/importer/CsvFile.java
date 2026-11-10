package app.svault.desktop.feature.importer;

import app.svault.core.feature.importer.CsvSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * desktop 選的 CSV 檔（{@link CsvPicker} 給的路徑；T-117 起匯入精靈的狀態在 core，只認 {@link CsvSource}）。
 *
 * @param path 檔案路徑
 */
record CsvFile(Path path) implements CsvSource {

    @Override
    public InputStream open() throws IOException {
        return Files.newInputStream(path);
    }

    /** @return 檔名（預覽的來源） */
    @Override
    public String name() {
        Path file = path.getFileName();
        return file == null ? "" : file.toString();
    }

    /** @return 完整路徑（完成頁提示刪除，AC-14-3） */
    @Override
    public String location() {
        return path.toString();
    }

    /** 不輸出路徑：路徑可能含使用者名稱，不進日誌（CONVENTIONS §2）。 */
    @Override
    public String toString() {
        return "CsvFile[]";
    }
}
