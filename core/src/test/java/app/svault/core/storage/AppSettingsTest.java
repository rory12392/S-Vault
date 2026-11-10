package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 兩平台共用的設定值（T-23）：自動上鎖、剪貼簿秒數、主題的預設值、範圍與讀寫。
 */
class AppSettingsTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("AC-12-1、AC-12-2、AC-05-1：沒有設定時 PC 閒置 5 分鐘、手機背景 30 秒、剪貼簿 30 秒、主題跟隨系統")
    void defaults() {
        Settings s = Settings.defaults();
        assertEquals(5, s.autoLockDesktopMinutes());
        assertEquals(30, s.autoLockAndroidSeconds());
        assertEquals(30, s.clipboardClearSeconds());
        assertEquals(Settings.THEME_SYSTEM, s.theme());
        assertEquals(true, s.blockScreenCapture(), "I-1：Windows 防截圖預設開（D-189）");
        assertEquals(false, s.with(Settings.BLOCK_SCREEN_CAPTURE, Boolean.FALSE).blockScreenCapture());
    }

    @Test
    @DisplayName("AC-12-1：閒置上鎖可設 1–60 分鐘，0 是不自動上鎖；超出範圍拒絕寫入")
    void desktopMinutesRange() {
        IntSetting minutes = Settings.AUTO_LOCK_DESKTOP_MINUTES;
        assertEquals(0, minutes.write(Settings.defaults(), 0).autoLockDesktopMinutes());
        assertEquals(1, minutes.write(Settings.defaults(), 1).autoLockDesktopMinutes());
        assertEquals(60, minutes.write(Settings.defaults(), 60).autoLockDesktopMinutes());
        assertThrows(IllegalArgumentException.class, () -> minutes.write(Settings.defaults(), 61));
        assertThrows(IllegalArgumentException.class, () -> minutes.write(Settings.defaults(), -1));
        assertEquals(Settings.AUTO_LOCK_OFF, 0);
    }

    @Test
    @DisplayName("AC-12-2、AC-05-1：手機背景秒數 0–3600、剪貼簿 10–300 秒")
    void otherRanges() {
        assertEquals(0, Settings.AUTO_LOCK_ANDROID_SECONDS.write(Settings.defaults(), 0).autoLockAndroidSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> Settings.AUTO_LOCK_ANDROID_SECONDS.write(Settings.defaults(), 3601));
        assertEquals(10, Settings.CLIPBOARD_CLEAR_SECONDS.write(Settings.defaults(), 10).clipboardClearSeconds());
        assertEquals(300, Settings.CLIPBOARD_CLEAR_SECONDS.write(Settings.defaults(), 300).clipboardClearSeconds());
        assertThrows(IllegalArgumentException.class,
                () -> Settings.CLIPBOARD_CLEAR_SECONDS.write(Settings.defaults(), 9));
    }

    @Test
    @DisplayName("檔案裡的值超出範圍或型別不對（手改、較舊的程式）時用預設值，不讓程式打不開")
    void invalidStoredValuesFallBack() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.write(file, ("{\"schemaVersion\":1,\"autoLock\":{\"desktopMinutes\":999,\"androidSeconds\":\"x\"},"
                + "\"clipboard\":{\"clearSeconds\":-5},\"theme\":\"purple\"}").getBytes(StandardCharsets.UTF_8));
        Settings s = new SettingsStore(file).load();
        assertEquals(5, s.autoLockDesktopMinutes());
        assertEquals(30, s.autoLockAndroidSeconds());
        assertEquals(30, s.clipboardClearSeconds());
        assertEquals(Settings.THEME_SYSTEM, s.theme());
    }

    @Test
    @DisplayName("寫入後讀回；存成巢狀物件（autoLock.desktopMinutes 等）")
    void roundTrip() throws IOException {
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
        Settings changed = Settings.CLIPBOARD_CLEAR_SECONDS.write(
                Settings.AUTO_LOCK_DESKTOP_MINUTES.write(Settings.defaults(), 15), 45).withTheme(Settings.THEME_DARK);
        store.save(changed);

        Settings s = store.load();
        assertEquals(15, s.autoLockDesktopMinutes());
        assertEquals(45, s.clipboardClearSeconds());
        assertEquals(Settings.THEME_DARK, s.theme());
        assertEquals(Optional.of(15L), s.number("autoLock.desktopMinutes"));
        assertEquals(Optional.of(45L), s.number("clipboard.clearSeconds"));
    }

    @Test
    @DisplayName("主題只接受 light／dark／system")
    void themeValues() {
        assertEquals(Settings.THEME_LIGHT, Settings.defaults().withTheme("light").theme());
        assertEquals(Settings.THEME_SYSTEM, Settings.defaults().withTheme("dark").withTheme("system").theme());
        assertThrows(IllegalArgumentException.class, () -> Settings.defaults().withTheme("purple"));
    }

    @Test
    @DisplayName("T-23 之前寫的 settings.json（版本 1、沒有這些鍵）不需要升級：讀到預設值、不產生 .bak")
    void olderFileNeedsNoMigration() throws IOException {
        Path file = tmp.resolve("settings.json");
        Files.write(file, "{\"schemaVersion\":1,\"theme\":\"dark\"}".getBytes(StandardCharsets.UTF_8));
        Settings s = new SettingsStore(file).load();
        assertEquals(Settings.THEME_DARK, s.theme());
        assertEquals(5, s.autoLockDesktopMinutes());
        assertEquals(1, SettingsStore.SCHEMA_VERSION);
        assertFalse(Files.exists(tmp.resolve("settings.json.v1.bak")));
        assertTrue(Files.exists(file));
    }
}
