package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.Contribution;
import app.svault.core.module.FeatureModule;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.PasswordFieldAction;
import app.svault.desktop.shell.fake.ThrowingDesktopFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javafx.scene.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * desktop 的故障注入測試：模組的 attach、擴充點、頁面工廠、按鈕動作丟例外時，程式不結束、其他模組照常、外殼收到通知。
 * T-22 起也涵蓋真的模組（GEN、HLT、IMP）：把它們包成每個進入點都丟例外的版本。
 */
class ModuleFaultTest {

    private final List<String> failures = new ArrayList<>();

    private ModuleRegistry<DesktopFeature> discover() {
        return DesktopModules.discover(ModuleSettings.DEFAULTS, (id, action, error) -> failures.add(id + ":" + action));
    }

    @Test
    @DisplayName("建構子失敗的模組被略過，其他模組照常")
    void brokenConstructor() {
        List<String> ids = discover().all().stream().map(FeatureModule::id).collect(Collectors.toList());
        assertEquals(6, ids.size(), ids.toString());
        assertFalse(ids.contains("broken"));
        assertTrue(failures.contains("?:discover"), failures.toString());
    }

    @Test
    @DisplayName("設定段落擴充點丟例外：略過該模組，其他模組的設定段落照常")
    void faultyExtensionPoint() {
        List<String> sections = discover().collect("settingsSections", DesktopFeature::settingsSections).stream()
                .filter(c -> !ModuleDisableTest.REAL.contains(c.moduleId()))
                .map(c -> c.value().title()).collect(Collectors.toList());
        assertEquals(List.of("alpha-settings"), sections);
        assertTrue(failures.contains("faulty:settingsSections"), failures.toString());
    }

    @Test
    @DisplayName("頁面工廠（工具列選單的模組頁，T-30）與按鈕動作丟例外：包在 ModuleGuard 裡呼叫，回傳空結果並通知外殼")
    void faultyFactoriesAndActions() {
        ModuleRegistry<DesktopFeature> registry = discover();
        ModuleGuard guard = registry.guard();
        for (ModuleMenu.Page page : ModuleMenu.pages(registry)) {
            if (ModuleDisableTest.REAL.contains(page.moduleId())) {
                continue;
            }
            // 空＝ModulePage 顯示「這個功能暫時無法使用」；選單項目本身照常
            Optional<Node> node = page.create().get();
            assertEquals(!page.moduleId().equals("faulty"), node.isPresent(), page.title());
        }
        for (Contribution<PasswordFieldAction> action
                : registry.collect("passwordFieldActions", DesktopFeature::passwordFieldActions)) {
            if (ModuleDisableTest.REAL.contains(action.moduleId())) {
                continue;
            }
            Optional<char[]> value = guard.call(action.moduleId(), "passwordFieldAction", action.value().action());
            assertEquals(!action.moduleId().equals("faulty"), value.isPresent(), action.value().label());
        }
        assertTrue(failures.contains("faulty:createPage"), failures.toString());
        assertTrue(failures.contains("faulty:passwordFieldAction"), failures.toString());
    }

    @Test
    @DisplayName("T-30：模組沒給標題時選單用模組名稱；頁面工廠是 null 時當成建立失敗")
    void menuTitleFallbackAndNullFactory() {
        DesktopFeature blank = new DesktopFeature() {
            @Override
            public String id() {
                return "blank";
            }

            @Override
            public String displayName() {
                return "沒有標題的模組";
            }

            @Override
            public List<MenuPage> menuPages() {
                return List.of(new MenuPage(" ", null));
            }
        };
        ModuleRegistry<DesktopFeature> registry = ModuleRegistry.of(List.of(blank), ModuleSettings.DEFAULTS,
                new ModuleGuard((id, action, error) -> failures.add(id + ":" + action)));
        List<ModuleMenu.Page> pages = ModuleMenu.pages(registry);
        assertEquals(1, pages.size());
        assertEquals("沒有標題的模組", pages.get(0).title());
        assertTrue(pages.get(0).create().get().isEmpty());
    }

    @Test
    @DisplayName("attach 丟例外的模組被停用，其他模組照常 attach")
    void faultyAttach() {
        LiveSettings live = LiveSettings.inMemory(Settings.defaults());
        List<String> attached = new ArrayList<>();
        ModuleRegistry<DesktopFeature> registry = DesktopModules.discover(live.current(),
                (id, action, error) -> failures.add(id + ":" + action), id -> {
                    attached.add(id);
                    return DesktopModules.hosts(live, Runnable::run, x -> { }).apply(id);
                });
        assertTrue(failures.contains("faulty:attach"), failures.toString());
        assertFalse(registry.isEnabled("faulty"));
        for (String id : List.of("alpha", "beta", "generator", "health", "importer")) {
            assertTrue(registry.isEnabled(id), id);
            assertTrue(attached.contains(id), id);
        }
    }

    @Test
    @DisplayName("真的模組每個進入點都丟例外：逐一被接住並指名，其他模組的擴充照常")
    void realModulesFault() {
        List<DesktopFeature> modules = new ArrayList<>();
        for (DesktopFeature module : discover().all()) {
            modules.add(ModuleDisableTest.REAL.contains(module.id()) ? new ThrowingDesktopFeature(module) : module);
        }
        failures.clear();
        ModuleGuard guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
        ModuleRegistry<DesktopFeature> registry = ModuleRegistry.of(modules, ModuleSettings.DEFAULTS, guard);

        for (DesktopFeature module : registry.enabled()) {
            guard.run(module.id(), "attach", () -> module.attach(null));
        }
        // 工具列選單（T-30）：真模組的 menuPages 丟例外 → 沒有它們的項目，假模組的照常
        List<String> pages = ModuleMenu.pages(registry).stream()
                .map(ModuleMenu.Page::moduleId).collect(Collectors.toList());
        List<String> actions = registry.collect("passwordFieldActions", DesktopFeature::passwordFieldActions).stream()
                .map(Contribution::moduleId).collect(Collectors.toList());
        List<String> sections = registry.collect("settingsSections", DesktopFeature::settingsSections).stream()
                .map(Contribution::moduleId).collect(Collectors.toList());

        assertEquals(List.of("alpha", "beta", "faulty"), pages);
        assertEquals(List.of("alpha", "faulty"), actions);
        assertEquals(List.of("alpha"), sections);
        for (String id : ModuleDisableTest.REAL) {
            for (String action : List.of("attach", "menuPages", "passwordFieldActions", "settingsSections")) {
                assertTrue(failures.contains(id + ":" + action), id + ":" + action + " " + failures);
            }
        }
    }

    @Test
    @DisplayName("真的模組本身（空殼或模組分支的內容）在錯誤邊界裡呼叫每個進入點都不出錯")
    void realModulesAreHealthy() {
        LiveSettings live = LiveSettings.inMemory(Settings.defaults());
        ModuleRegistry<DesktopFeature> registry = DesktopModules.discover(live.current(),
                (id, action, error) -> failures.add(id + ":" + action),
                DesktopModules.hosts(live, Runnable::run, x -> { }));
        ModuleMenu.pages(registry);
        registry.collect("menuPages", DesktopFeature::menuPages);
        registry.collect("passwordFieldActions", DesktopFeature::passwordFieldActions);
        registry.collect("settingsSections", DesktopFeature::settingsSections);
        for (String id : ModuleDisableTest.REAL) {
            assertTrue(registry.isEnabled(id), id);
            assertFalse(failures.stream().anyMatch(f -> f.startsWith(id + ":")), failures.toString());
        }
    }
}
