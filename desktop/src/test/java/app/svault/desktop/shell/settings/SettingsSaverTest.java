package app.svault.desktop.shell.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.core.storage.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 設定畫面的變更：立即生效、在背景寫進 settings.json、寫不進去時通知畫面。
 */
class SettingsSaverTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("AC-12-1：改閒置上鎖分鐘數立即生效，背景寫進 settings.json")
    void changeAppliesAndSaves() {
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
        LiveSettings live = LiveSettings.load(store);
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger failures = new AtomicInteger();
        SettingsSaver saver = new SettingsSaver(live, queued::add, Runnable::run, failures::incrementAndGet);

        saver.change(s -> Settings.AUTO_LOCK_DESKTOP_MINUTES.write(s, 15));

        // 記憶體中立即生效；檔案要等背景工作
        assertEquals(15, live.current().autoLockDesktopMinutes());
        assertEquals(5, store.load().autoLockDesktopMinutes());
        queued.forEach(Runnable::run);
        assertEquals(15, store.load().autoLockDesktopMinutes());
        assertEquals(0, failures.get());
    }

    @Test
    @DisplayName("連續變更時，晚執行的寫入也寫最新的值，不會蓋回舊值")
    void laterWriteKeepsLatest() {
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
        LiveSettings live = LiveSettings.load(store);
        List<Runnable> queued = new ArrayList<>();
        SettingsSaver saver = new SettingsSaver(live, queued::add, Runnable::run, () -> { });

        saver.change(s -> s.withTheme(Settings.THEME_DARK));
        saver.change(s -> s.withTheme(Settings.THEME_LIGHT));
        queued.get(0).run();

        assertEquals(Settings.THEME_LIGHT, store.load().theme());
    }

    @Test
    @DisplayName("寫不進 settings.json 時回到畫面執行緒通知錯誤，設定照常生效")
    void saveFailureNotifies() throws IOException {
        Path dir = tmp.resolve("settings.json");
        Files.createDirectories(dir.resolve("x"));
        LiveSettings live = LiveSettings.load(new SettingsStore(dir));
        AtomicInteger failures = new AtomicInteger();
        List<Runnable> ui = new ArrayList<>();
        Executor direct = Runnable::run;
        SettingsSaver saver = new SettingsSaver(live, direct, ui::add, failures::incrementAndGet);

        saver.change(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 60));

        assertEquals(0, failures.get(), "通知要回到 UI 執行緒才執行");
        ui.forEach(Runnable::run);
        assertEquals(1, failures.get());
        assertEquals(60, live.current().clipboardClearSeconds());
    }
}
