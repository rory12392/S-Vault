package app.svault.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

/**
 * {@code settings.json} 的讀寫（Architecture §2.2）。格式變更依 {@code schemaVersion} 升級（{@link VersionedJsonFile}）。
 */
public final class SettingsStore {

    /** 目前的格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    // 格式升級步驟：只新增、不改寫舊的步驟（Architecture §2.2）。版本 0＝T-05 之前沒有版本欄位的檔案，內容相同
    private static final List<VersionedJsonFile.Migration> MIGRATIONS = Collections.singletonList(
            new VersionedJsonFile.Migration(0, doc -> doc));

    private final VersionedJsonFile file;

    /**
     * @param file {@code settings.json} 的路徑
     */
    public SettingsStore(Path file) {
        this.file = new VersionedJsonFile(file, SCHEMA_VERSION, MIGRATIONS);
    }

    /**
     * @return 目前的設定；檔案不存在或無法解析時是預設值（不會丟例外，設定壞掉不能讓程式打不開）
     */
    public Settings load() {
        return new Settings(file.read());
    }

    /**
     * @param settings 要保存的設定
     * @throws IOException 寫入失敗
     */
    public void save(Settings settings) throws IOException {
        file.write(settings.content());
    }
}
