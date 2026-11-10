package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.core.storage.SettingsStore;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.ModuleHost;
import app.svault.desktop.shell.gallery.DemoModules;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * desktop 給模組的外殼服務（T-22）：模組設定改了立即生效，寫檔排到背景執行緒。
 */
class DesktopModulesTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("模組設定：立即生效、寫檔交給背景執行緒、只寫 modules.<id>.*")
    void preferencesApplyAndSaveInBackground() {
        SettingsStore store = new SettingsStore(tmp.resolve("settings.json"));
        LiveSettings live = LiveSettings.load(store);
        List<Runnable> queued = new ArrayList<>();
        ModulePreferences prefs = DesktopModules.hosts(live, queued::add, id -> { })
                .apply("generator").preferences();

        prefs.put("length", 24L);

        assertEquals(Optional.of(24L), live.current().number("modules.generator.length"));
        assertEquals(Optional.empty(), store.load().number("modules.generator.length"));
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(Optional.of(24L), store.load().number("modules.generator.length"));
    }

    @Test
    @DisplayName("T-30：每個模組的 host 拿到的都是外殼共用的同一個 EntryNavigator")
    void navigatorIsShared() {
        EntryNavigator navigator = id -> { };
        Function<String, ModuleHost> hosts = DesktopModules.hosts(LiveSettings.inMemory(Settings.defaults()),
                Runnable::run, navigator);
        assertSame(navigator, hosts.apply("health").navigator());
        assertSame(navigator, hosts.apply("importer").navigator());
    }

    @Test
    @DisplayName("T-30：--demo-modules 的模組排在探索結果之後，一樣 attach、一樣可以停用")
    void extraModules() {
        LiveSettings live = LiveSettings.inMemory(Settings.defaults().with("modules.beta.enabled", Boolean.FALSE));
        List<String> attached = new ArrayList<>();
        ModuleRegistry<DesktopFeature> registry = DesktopModules.discover(live.current(), (m, a, e) -> { },
                id -> {
                    attached.add(id);
                    return DesktopModules.hosts(live, Runnable::run, x -> { }).apply(id);
                }, DemoModules.all());
        List<String> ids = registry.all().stream().map(DesktopFeature::id).toList();
        assertEquals(List.of("demo", "demofaulty"), ids.subList(ids.size() - 2, ids.size()));
        assertTrue(attached.contains("demo"));
        assertFalse(attached.contains("beta"));
        assertTrue(registry.isEnabled("demofaulty"));
    }
}
