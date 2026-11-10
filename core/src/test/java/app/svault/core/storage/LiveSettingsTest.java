package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 執行中的設定（T-23）：變更立即生效並通知，寫檔另外呼叫。
 */
class LiveSettingsTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("變更立即生效、通知所有監聽者；save 寫進 settings.json")
    void updateNotifiesAndSaves() throws IOException {
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
        LiveSettings live = LiveSettings.load(store);
        List<Integer> seen = new ArrayList<>();
        live.addListener(now -> seen.add(now.autoLockDesktopMinutes()));

        Settings now = live.update(s -> Settings.AUTO_LOCK_DESKTOP_MINUTES.write(s, 10));

        assertEquals(10, now.autoLockDesktopMinutes());
        assertEquals(10, live.current().autoLockDesktopMinutes());
        assertEquals(List.of(10), seen);
        // 還沒寫檔
        assertEquals(5, store.load().autoLockDesktopMinutes());
        live.save();
        assertEquals(10, store.load().autoLockDesktopMinutes());
    }

    @Test
    @DisplayName("啟動時讀進檔案的內容")
    void loadsExisting() throws IOException {
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
        store.save(Settings.CLIPBOARD_CLEAR_SECONDS.write(Settings.defaults(), 60));
        assertEquals(60, LiveSettings.load(store).current().clipboardClearSeconds());
    }

    @Test
    @DisplayName("寫檔失敗時丟 IOException，記憶體中的設定照常生效")
    void saveFailureKeepsCurrent() throws IOException {
        // 把 settings.json 的位置變成資料夾，寫不進去
        Path dir = tmp.resolve("settings.json");
        Files.createDirectories(dir.resolve("x"));
        LiveSettings live = LiveSettings.load(new SettingsStore(dir));
        live.update(s -> s.withTheme(Settings.THEME_DARK));
        assertThrows(IOException.class, live::save);
        assertEquals(Settings.THEME_DARK, live.current().theme());
    }

    @Test
    @DisplayName("沒有本機資料夾時（只在記憶體）：save 不做事")
    void memoryOnly() throws IOException {
        LiveSettings live = LiveSettings.inMemory(Settings.defaults());
        live.update(s -> s.withTheme(Settings.THEME_LIGHT));
        live.save();
        assertEquals(Settings.THEME_LIGHT, live.current().theme());
    }

    @Test
    @DisplayName("移除監聽者後不再通知")
    void removeListener() {
        LiveSettings live = LiveSettings.inMemory(Settings.defaults());
        List<String> seen = new ArrayList<>();
        LiveSettings.Listener listener = now -> seen.add(now.theme());
        live.addListener(listener);
        live.removeListener(listener);
        live.update(s -> s.withTheme(Settings.THEME_DARK));
        assertEquals(List.of(), seen);
    }
}
