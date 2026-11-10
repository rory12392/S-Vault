package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.Contribution;
import app.svault.core.module.FeatureModule;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.module.ModuleToggle;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.desktop.shell.ext.DesktopFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * desktop 的停用測試：逐一停用探索到的每個模組（APK 裡真的 GEN、HLT、IMP 與測試的假模組），其他模組的頁面、按鈕、
 * 設定段落照常（Architecture §1.1；T-22 起涵蓋真的模組）。
 */
class ModuleDisableTest {

    /** desktop 的真模組（META-INF/services，T-22）。 */
    static final Set<String> REAL = Set.of("generator", "health", "importer");

    private static List<String> owners(ModuleRegistry<DesktopFeature> registry) {
        List<String> out = new ArrayList<>();
        List<Function<DesktopFeature, List<?>>> points = List.of(
                DesktopFeature::menuPages, DesktopFeature::passwordFieldActions, DesktopFeature::settingsSections);
        for (Function<DesktopFeature, List<?>> point : points) {
            registry.collect("point", point::apply).stream().map(Contribution::moduleId).forEach(out::add);
        }
        return out;
    }

    private static List<String> menuOwners(ModuleRegistry<DesktopFeature> registry) {
        return ModuleMenu.pages(registry).stream().map(ModuleMenu.Page::moduleId).collect(Collectors.toList());
    }

    @Test
    @DisplayName("探索得到三個真的模組，代號與名稱（模組自己的 messages.properties）都有")
    void realModulesAreDiscovered() {
        ModuleRegistry<DesktopFeature> all = DesktopModules.discover(ModuleSettings.DEFAULTS, ModuleFailureListener.NONE);
        Set<String> ids = all.all().stream().map(FeatureModule::id).collect(Collectors.toSet());
        assertEquals(Set.of("alpha", "beta", "faulty", "generator", "health", "importer"), ids);
        for (DesktopFeature module : all.all()) {
            if (REAL.contains(module.id())) {
                assertFalse(module.displayName().isBlank(), module.id());
                assertTrue(module.getClass().getName().startsWith("app.svault.desktop.feature." + module.id() + "."),
                        module.getClass().getName());
            }
        }
    }

    /** 正式程式的探索方式（含 attach）；{@code attached} 記下被 attach 的模組。 */
    private static ModuleRegistry<DesktopFeature> discover(Settings settings, List<String> attached) {
        LiveSettings live = LiveSettings.inMemory(settings);
        return DesktopModules.discover(live.current(), ModuleFailureListener.NONE, moduleId -> {
            attached.add(moduleId);
            return DesktopModules.hosts(live, Runnable::run, id -> { }).apply(moduleId);
        });
    }

    @Test
    @DisplayName("逐一停用每個 desktop 模組：該模組的擴充消失、也不呼叫 attach，其他照常")
    void disableEachModule() {
        ModuleRegistry<DesktopFeature> all = discover(Settings.defaults(), new ArrayList<>());
        List<String> ids = all.all().stream().map(FeatureModule::id).collect(Collectors.toList());
        assertTrue(ids.containsAll(REAL), ids.toString());

        for (String id : ids) {
            List<String> attached = new ArrayList<>();
            ModuleRegistry<DesktopFeature> registry = discover(
                    Settings.defaults().with("modules." + id + ".enabled", Boolean.FALSE), attached);
            assertFalse(registry.isEnabled(id));
            assertFalse(attached.contains(id), "停用的 " + id + " 不該 attach");
            List<String> expected = owners(all).stream().filter(m -> !m.equals(id)).collect(Collectors.toList());
            assertEquals(expected, owners(registry), "停用 " + id + " 後");
            // 工具列選單（T-30）也一樣：停用的模組沒有項目
            assertEquals(menuOwners(all).stream().filter(m -> !m.equals(id)).collect(Collectors.toList()),
                    menuOwners(registry), "停用 " + id + " 後的選單");
            for (String other : ids) {
                // faulty 的 attach 會失敗而被停用（ModuleFaultTest），其他沒停用的都照常啟用
                if (!other.equals(id) && !other.equals("faulty")) {
                    assertTrue(registry.isEnabled(other), "停用 " + id + " 後 " + other);
                }
            }
        }
    }

    @Test
    @DisplayName("T-38：逐一停用每個 desktop 模組後，設定頁「功能模組」仍列出它；從那裡開啟、重新啟動後擴充回來")
    void reenableEachModuleFromSettings() {
        ModuleRegistry<DesktopFeature> all = discover(Settings.defaults(), new ArrayList<>());
        List<String> ids = all.all().stream().map(FeatureModule::id).collect(Collectors.toList());

        for (String id : ids) {
            Settings disabled = Settings.defaults().withModuleEnabled(id, false);
            ModuleRegistry<DesktopFeature> registry = discover(disabled, new ArrayList<>());
            assertEquals(ids, registry.toggles(disabled).stream().map(ModuleToggle::id).collect(Collectors.toList()),
                    "停用 " + id + " 後的功能模組區段");
            ModuleToggle row = registry.toggles(disabled).stream().filter(t -> t.id().equals(id)).findFirst()
                    .orElseThrow();
            assertFalse(row.enabled() || row.running(), id);

            // 設定頁的開關做的事（ModuleToggleRows）：寫 modules.<id>.enabled=true；下次啟動以新設定探索
            Settings reenabled = disabled.withModuleEnabled(id, true);
            assertTrue(registry.toggles(reenabled).stream().filter(t -> t.id().equals(id)).findFirst()
                    .orElseThrow().restartNeeded(), "重新啟動前 " + id);
            List<String> attached = new ArrayList<>();
            ModuleRegistry<DesktopFeature> restarted = discover(reenabled, attached);
            if (!id.equals("faulty")) {
                assertTrue(restarted.isEnabled(id), id);
                assertTrue(attached.contains(id), id);
                assertEquals(owners(all), owners(restarted), "重新開啟 " + id + " 後");
            }
        }
    }

    @Test
    @DisplayName("T-30：工具列選單的模組頁面——停用的模組沒有項目，其他模組的項目與標題照常")
    void disabledModuleHasNoMenuItem() {
        // 不 attach（faulty 的 attach 會失敗而被停用）：faulty 的頁面工廠會丟例外，但項目照常出現（ModuleFaultTest）
        List<String> all = ModuleMenu.pages(DesktopModules.discover(Settings.defaults(), ModuleFailureListener.NONE))
                .stream().map(p -> p.moduleId() + ":" + p.title()).collect(Collectors.toList());
        assertTrue(all.containsAll(List.of("alpha:alpha-page", "beta:beta-page", "faulty:faulty-page")),
                all.toString());

        List<String> withoutAlpha = ModuleMenu.pages(DesktopModules.discover(
                Settings.defaults().with("modules.alpha.enabled", Boolean.FALSE), ModuleFailureListener.NONE))
                .stream().map(p -> p.moduleId() + ":" + p.title()).collect(Collectors.toList());
        assertEquals(all.stream().filter(p -> !p.startsWith("alpha:")).collect(Collectors.toList()), withoutAlpha);
    }

    @Test
    @DisplayName("沒有設定時三個真的模組都預設啟用")
    void realModulesEnabledByDefault() {
        ModuleRegistry<DesktopFeature> all = DesktopModules.discover(
                id -> Optional.empty(), ModuleFailureListener.NONE);
        for (String id : REAL) {
            assertTrue(all.isEnabled(id), id);
        }
    }
}
