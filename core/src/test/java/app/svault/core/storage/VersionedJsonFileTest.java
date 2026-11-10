package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code schemaVersion} 升級機制（Architecture §2.2）。
 */
class VersionedJsonFileTest {

    @TempDir
    Path tmp;

    // 範例升級：v1 → v2 把 autoLockMinutes 搬到 autoLock.desktopMinutes；v2 → v3 新增 clipboardSeconds
    private static final List<VersionedJsonFile.Migration> STEPS = List.of(
            new VersionedJsonFile.Migration(1, doc -> {
                Object minutes = doc.remove("autoLockMinutes");
                doc.put("autoLock", new java.util.LinkedHashMap<>(Map.of("desktopMinutes", minutes)));
                return doc;
            }),
            new VersionedJsonFile.Migration(2, doc -> {
                doc.put("clipboardSeconds", 30L);
                return doc;
            }));

    private Path write(String name, String json) throws IOException {
        Path file = tmp.resolve(name);
        Files.write(file, json.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static String read(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("依版本依序升級，升級前先複製 .bak，升級結果寫回")
    void migratesInOrderWithBackup() throws IOException {
        String original = "{\"schemaVersion\": 1, \"autoLockMinutes\": 5, \"theme\": \"dark\"}";
        Path file = write("settings.json", original);

        Map<String, Object> doc = new VersionedJsonFile(file, 3, STEPS).read();

        assertEquals(3L, doc.get("schemaVersion"));
        assertEquals(Map.of("desktopMinutes", 5L), doc.get("autoLock"));
        assertEquals(30L, doc.get("clipboardSeconds"));
        assertEquals("dark", doc.get("theme"));
        assertEquals(original, read(tmp.resolve("settings.json.v1.bak")));
        // 寫回後再讀不會再升級（也不會再產生 .bak）
        Files.delete(tmp.resolve("settings.json.v1.bak"));
        Map<String, Object> again = new VersionedJsonFile(file, 3, STEPS).read();
        assertEquals(doc, again);
        assertFalse(Files.exists(tmp.resolve("settings.json.v1.bak")));
    }

    @Test
    @DisplayName("沒有 schemaVersion 的檔案視為版本 0")
    void missingVersionIsZero() throws IOException {
        Path file = write("recent.json", "{\"vaults\": []}");
        List<VersionedJsonFile.Migration> steps = List.of(new VersionedJsonFile.Migration(0, doc -> {
            doc.put("migrated", true);
            return doc;
        }));
        Map<String, Object> doc = new VersionedJsonFile(file, 1, steps).read();
        assertEquals(1L, doc.get("schemaVersion"));
        assertEquals(Boolean.TRUE, doc.get("migrated"));
        assertTrue(Files.exists(tmp.resolve("recent.json.v0.bak")));
    }

    @Test
    @DisplayName("比程式新的版本原樣讀取、不升級也不改檔")
    void newerVersionUntouched() throws IOException {
        String json = "{\"schemaVersion\": 9, \"future\": \"x\"}";
        Path file = write("settings.json", json);
        Map<String, Object> doc = new VersionedJsonFile(file, 3, STEPS).read();
        assertEquals(9L, doc.get("schemaVersion"));
        assertEquals("x", doc.get("future"));
        assertEquals(json, read(file));
    }

    @Test
    @DisplayName("無法解析時保留成 .corrupt，回傳只有版本的新物件")
    void corruptFileIsKept() throws IOException {
        Path file = write("settings.json", "{壞掉");
        Map<String, Object> doc = new VersionedJsonFile(file, 3, STEPS).read();
        assertEquals(Map.of("schemaVersion", 3L), doc);
        assertEquals("{壞掉", read(tmp.resolve("settings.json.corrupt")));
    }

    @Test
    @DisplayName("Codex 待確認：超過大小上限的檔案不整個讀進記憶體，當成損壞保留，回傳新物件")
    void oversizedFileIsKeptAsCorrupt() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.write(file, new byte[VersionedJsonFile.MAX_BYTES + 1]);
        Map<String, Object> doc = new VersionedJsonFile(file, 3, STEPS).read();
        assertEquals(Map.of("schemaVersion", 3L), doc);
        assertTrue(Files.exists(tmp.resolve("settings.json.corrupt")));
    }

    @Test
    @DisplayName("Codex 待確認：本機檔讀取剛好等於上限可以，多一個位元組就丟 TOO_LARGE")
    void boundedFileRead() throws IOException, VaultIoException {
        Path file = tmp.resolve("base.kdbx");
        Files.write(file, new byte[10]);
        assertEquals(10, BoundedRead.file(file, 10).length);
        VaultIoException e = assertThrows(VaultIoException.class, () -> BoundedRead.file(file, 9));
        assertEquals(VaultIoException.Reason.TOO_LARGE, e.reason());
    }

    @Test
    @DisplayName("檔案不存在時回傳只有版本的新物件，不建立檔案")
    void missingFile() {
        Path file = tmp.resolve("none.json");
        assertEquals(Map.of("schemaVersion", 3L), new VersionedJsonFile(file, 3, STEPS).read());
        assertFalse(Files.exists(file));
    }

    @Test
    @DisplayName("缺少某個版本的升級步驟是程式錯誤")
    void missingStepFails() throws IOException {
        Path file = write("settings.json", "{\"schemaVersion\": 1, \"autoLockMinutes\": 5}");
        assertThrows(IllegalStateException.class,
                () -> new VersionedJsonFile(file, 3, STEPS.subList(0, 1)).read());
    }

    @Test
    @DisplayName("寫入時補上目前版本，內容是 UTF-8 的 JSON")
    void writeAddsVersion() throws IOException {
        Path file = tmp.resolve("out.json");
        new VersionedJsonFile(file, 3, STEPS).write(new java.util.LinkedHashMap<>(Map.of("theme", "淺色")));
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) Json.parse(read(file));
        assertEquals(3L, doc.get("schemaVersion"));
        assertEquals("淺色", doc.get("theme"));
    }
}
