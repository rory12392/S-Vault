package app.svault.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 有 {@code schemaVersion} 的 JSON 檔（{@code settings.json}、{@code recent.json}，Architecture §2.2）。
 *
 * <p>讀取時依版本依序升級；升級前把原檔複製成 {@code <檔名>.v<舊版本>.bak}。升級步驟只新增、不改寫舊的步驟。
 * 沒有 {@code schemaVersion} 的檔案視為版本 0。比目前程式新的版本（降版執行）原樣讀取、不升級。
 */
final class VersionedJsonFile {

    private static final Logger LOG = Logger.getLogger(VersionedJsonFile.class.getName());

    /** 設定與最近清單的大小上限（實際只有幾 KB）。 */
    static final int MAX_BYTES = 4 * 1024 * 1024;

    /** 格式版本欄位的名稱。 */
    static final String VERSION_KEY = "schemaVersion";

    /**
     * 一個升級步驟：把版本 {@code fromVersion} 的內容改成 {@code fromVersion + 1}。
     *
     * @param fromVersion 升級前的版本
     * @param step        升級內容（可以直接修改傳入的 Map 並回傳它）
     */
    record Migration(int fromVersion, UnaryOperator<Map<String, Object>> step) {
    }

    private final Path file;
    private final int currentVersion;
    private final List<Migration> migrations;

    /**
     * @param file           檔案路徑
     * @param currentVersion 程式目前的格式版本
     * @param migrations     升級步驟（每個舊版本一個）
     */
    VersionedJsonFile(Path file, int currentVersion, List<Migration> migrations) {
        this.file = Objects.requireNonNull(file, "file");
        this.currentVersion = currentVersion;
        this.migrations = List.copyOf(migrations);
    }

    /**
     * @return 檔案內容（已升級到目前版本）；檔案不存在時是只有 {@code schemaVersion} 的新物件。
     *         無法解析時把原檔改名保留成 {@code <檔名>.corrupt}、記錄日誌，回傳新物件
     * @throws IllegalStateException 缺少某個舊版本的升級步驟（程式錯誤）
     */
    Map<String, Object> read() {
        String text;
        try {
            text = new String(BoundedRead.file(file, MAX_BYTES), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            return fresh();
        } catch (VaultIoException e) {
            // 設定與最近清單只有幾 KB：大到這種程度一定不是 S-Vault 寫的，當成損壞保留下來
            keepCorrupt();
            return fresh();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "讀不到 {0}：{1}", new Object[] {file.getFileName(), e.getClass().getName()});
            return fresh();
        }
        Map<String, Object> doc;
        try {
            doc = asObject(Json.parse(text));
        } catch (IllegalArgumentException e) {
            keepCorrupt();
            return fresh();
        }
        int version = versionOf(doc);
        if (version >= currentVersion) {
            return doc;
        }
        backupBeforeMigration(version);
        for (int v = version; v < currentVersion; v++) {
            doc = stepFrom(v).step().apply(doc);
            doc.put(VERSION_KEY, (long) (v + 1));
        }
        try {
            write(doc);
        } catch (IOException e) {
            // 寫不回去下次再升級一次即可（升級步驟是決定性的）
            LOG.log(Level.WARNING, "升級後寫回 {0} 失敗：{1}",
                    new Object[] {file.getFileName(), e.getClass().getName()});
        }
        return doc;
    }

    /**
     * @param content 要寫入的內容（沒有 {@code schemaVersion} 時補上目前版本）
     * @throws IOException 寫入失敗
     */
    void write(Map<String, Object> content) throws IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        // schemaVersion 放第一個，人眼看檔案時比較清楚
        doc.put(VERSION_KEY, content.containsKey(VERSION_KEY) ? content.get(VERSION_KEY) : (long) currentVersion);
        doc.putAll(content);
        AtomicFiles.write(file, Json.write(doc).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> fresh() {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(VERSION_KEY, (long) currentVersion);
        return doc;
    }

    private Migration stepFrom(int version) {
        for (Migration m : migrations) {
            if (m.fromVersion() == version) {
                return m;
            }
        }
        throw new IllegalStateException(file.getFileName() + " 缺少從版本 " + version + " 升級的步驟");
    }

    private void backupBeforeMigration(int version) {
        Path bak = file.resolveSibling(file.getFileName() + ".v" + version + ".bak");
        try {
            Files.copy(file, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "升級前備份 {0} 失敗：{1}", new Object[] {file.getFileName(), e.getClass().getName()});
        }
    }

    private void keepCorrupt() {
        // 不記內容（可能是使用者手改的任何東西），只記檔名
        LOG.log(Level.WARNING, "{0} 無法解析，已改名為 .corrupt 並使用預設值", file.getFileName());
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "保留壞掉的 {0} 失敗：{1}", new Object[] {file.getFileName(), e.getClass().getName()});
        }
    }

    private static int versionOf(Map<String, Object> doc) {
        Object v = doc.get(VERSION_KEY);
        return v instanceof Long ? (int) (long) (Long) v : 0;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException("最外層不是 JSON 物件");
        }
        return (Map<String, Object>) value;
    }
}
