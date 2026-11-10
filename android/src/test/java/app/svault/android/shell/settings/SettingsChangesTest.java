package app.svault.android.shell.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.core.storage.SettingsStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 設定的變更：立即生效、寫檔放背景、寫不進去時回報；連續變更只會寫最新的值。
 */
public class SettingsChangesTest {

    private Path dir;
    private final List<Runnable> background = new ArrayList<>();
    private final List<Runnable> main = new ArrayList<>();

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("t24-settings");
    }

    @After
    public void tearDown() throws IOException {
        try (var s = Files.walk(dir)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void ac12_2_changeTakesEffectAtOnceAndIsSavedInBackground() {
        SettingsStore store = new SettingsStore(dir.resolve("settings.json"));
        LiveSettings live = LiveSettings.load(store);
        List<Integer> heard = new ArrayList<>();
        live.addListener(now -> heard.add(now.autoLockAndroidSeconds()));
        SettingsChanges changes = new SettingsChanges(live, background::add, main::add);

        AtomicInteger failed = new AtomicInteger();
        changes.change(s -> Settings.AUTO_LOCK_ANDROID_SECONDS.write(s, 0), failed::incrementAndGet);
        // 記憶體中立即生效、通知已送出；檔案還沒寫
        assertEquals(0, live.current().autoLockAndroidSeconds());
        assertEquals(List.of(0), heard);
        assertEquals(30, store.load().autoLockAndroidSeconds());
        assertEquals(1, background.size());

        changes.change(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 120), failed::incrementAndGet);
        // 背景工作晚到：第一個寫入也寫最新的內容
        background.get(0).run();
        assertEquals(0, store.load().autoLockAndroidSeconds());
        assertEquals(120, store.load().clipboardClearSeconds());
        background.get(1).run();
        assertEquals(0, failed.get());
        assertTrue(main.isEmpty());
    }

    @Test
    public void saveFailureIsReportedOnMainThread() throws IOException {
        // settings.json 的位置是資料夾，寫不進去
        Path blocked = dir.resolve("settings.json");
        Files.createDirectories(blocked.resolve("x"));
        LiveSettings live = LiveSettings.load(new SettingsStore(blocked));
        SettingsChanges changes = new SettingsChanges(live, background::add, main::add);
        AtomicInteger failed = new AtomicInteger();

        changes.change(s -> s.withTheme(Settings.THEME_DARK), failed::incrementAndGet);
        background.get(0).run();
        // 回報要回到主執行緒才執行；設定照常生效
        assertEquals(0, failed.get());
        assertEquals(1, main.size());
        main.get(0).run();
        assertEquals(1, failed.get());
        assertEquals(Settings.THEME_DARK, live.current().theme());
    }
}
