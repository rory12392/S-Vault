package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code settings.json}：預設值、點分隔的鍵、主題、模組啟用狀態、保留不認得的鍵。
 */
class SettingsStoreTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("沒有檔案時是預設值：主題跟隨系統、模組沒有明確設定")
    void defaults() {
        Settings s = new SettingsStore(tmp.resolve("settings.json")).load();
        assertEquals("system", s.theme());
        assertEquals(Optional.empty(), s.enabled("health"));
        assertEquals(Optional.empty(), s.number("autoLock.desktopMinutes"));
    }

    @Test
    @DisplayName("寫入後讀回：主題、巢狀的鍵、模組停用")
    void roundTrip() throws IOException {
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
        store.save(Settings.defaults()
                .with(Settings.THEME, "dark")
                .with("autoLock.desktopMinutes", 5L)
                .with("modules.health.enabled", false));

        Settings s = store.load();
        assertEquals("dark", s.theme());
        assertEquals(Optional.of(5L), s.number("autoLock.desktopMinutes"));
        assertEquals(Optional.of(false), s.enabled("health"));
        assertEquals(Optional.of(false), s.bool("modules.health.enabled"));

        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) Json.parse(
                new String(Files.readAllBytes(tmp.resolve("settings.json")), StandardCharsets.UTF_8));
        assertEquals(Long.valueOf(SettingsStore.SCHEMA_VERSION), doc.get("schemaVersion"));
        assertEquals(Map.of("health", Map.of("enabled", false)), doc.get("modules"));
    }

    @Test
    @DisplayName("型別不符時當作沒有設定；with(null) 移除鍵")
    void wrongTypeAndRemoval() {
        Settings s = Settings.defaults().with(Settings.THEME, 3L).with("a.b", "x");
        assertEquals("system", s.theme());
        assertEquals(Optional.empty(), s.bool("a.b"));
        assertEquals(Optional.empty(), s.with("a.b", null).string("a.b"));
        // 原物件不變
        assertEquals(Optional.of("x"), s.string("a.b"));
    }

    @Test
    @DisplayName("不認得的鍵（例如較新版本或模組寫的）原樣保留")
    void keepsUnknownKeys() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.write(file, "{\"schemaVersion\":1,\"modules\":{\"gen\":{\"length\":20}},\"x\":[1,2]}"
                .getBytes(StandardCharsets.UTF_8));
        SettingsStore store = new SettingsStore(file);
        store.save(store.load().with(Settings.THEME, "light"));
        Settings s = store.load();
        assertEquals("light", s.theme());
        assertEquals(Optional.of(20L), s.number("modules.gen.length"));
        assertTrue(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("\"x\""));
    }

    @Test
    @DisplayName("檔案壞掉時用預設值，不丟例外")
    void corruptFallsBack() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.write(file, "not json".getBytes(StandardCharsets.UTF_8));
        Settings s = new SettingsStore(file).load();
        assertEquals("system", s.theme());
        // 壞掉的原檔保留下來，之後存檔不會蓋掉使用者還想救回的內容
        assertTrue(Files.exists(tmp.resolve("settings.json.corrupt")));
        assertFalse(Files.exists(file));
    }
}
