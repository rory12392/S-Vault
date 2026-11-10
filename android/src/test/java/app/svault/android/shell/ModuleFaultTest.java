package app.svault.android.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.fake.ThrowingAndroidFeature;
import app.svault.core.module.Contribution;
import app.svault.core.module.FeatureModule;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Android 的故障注入測試：模組建構失敗、擴充點丟例外或缺類別時，其他模組照常、外殼收到通知。T-22 起也涵蓋真的模組
 * （GEN、HLT、IMP、BIO、AF）：把它們包成每個擴充點都丟例外的版本。畫面層（設定段落、模組頁面在重建時）的錯誤狀態由
 * 儀器測試 {@code ModuleErrorScreensTest} 檢查。
 */
public class ModuleFaultTest {

    private final List<String> failures = new ArrayList<>();

    private ModuleRegistry<AndroidFeature> discover() {
        return AndroidModules.discover(ModuleSettings.DEFAULTS, (id, action, error) -> failures.add(id + ":" + action));
    }

    private static List<String> fakeOwners(List<? extends Contribution<?>> contributions) {
        return contributions.stream().map(Contribution::moduleId)
                .filter(id -> !ModuleDisableTest.REAL.contains(id)).collect(Collectors.toList());
    }

    @Test
    public void brokenConstructorIsSkipped() {
        List<String> ids = discover().all().stream().map(FeatureModule::id).collect(Collectors.toList());
        assertEquals(ids.toString(), 8, ids.size());
        assertFalse(ids.contains("broken"));
        assertTrue(failures.toString(), failures.contains("?:discover"));
    }

    @Test
    public void faultyExtensionPointsAreSkipped() {
        ModuleRegistry<AndroidFeature> registry = discover();
        assertEquals(List.of("alpha", "beta"), fakeOwners(registry.collect("menuPages", AndroidFeature::menuPages)));
        assertEquals(List.of("alpha"),
                fakeOwners(registry.collect("unlockProviders", AndroidFeature::unlockProviders)));
        assertTrue(failures.toString(), failures.contains("faulty:menuPages"));
        assertTrue(failures.toString(), failures.contains("faulty:unlockProviders"));
    }

    @Test
    public void realModulesFault() {
        List<AndroidFeature> modules = new ArrayList<>();
        for (AndroidFeature module : discover().all()) {
            modules.add(ModuleDisableTest.REAL.contains(module.id()) ? new ThrowingAndroidFeature(module) : module);
        }
        failures.clear();
        ModuleGuard guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
        ModuleRegistry<AndroidFeature> registry = ModuleRegistry.of(modules, ModuleSettings.DEFAULTS, guard);

        List<String> menus = registry.collect("menuPages", AndroidFeature::menuPages).stream()
                .map(Contribution::moduleId).collect(Collectors.toList());
        List<String> actions = registry.collect("passwordFieldActions", AndroidFeature::passwordFieldActions)
                .stream().map(Contribution::moduleId).collect(Collectors.toList());
        List<String> sections = registry.collect("settingsSections", AndroidFeature::settingsSections).stream()
                .map(Contribution::moduleId).collect(Collectors.toList());
        List<String> unlocks = registry.collect("unlockProviders", AndroidFeature::unlockProviders).stream()
                .map(Contribution::moduleId).collect(Collectors.toList());

        assertEquals(List.of("alpha", "beta"), menus);
        assertEquals(List.of("alpha"), actions);
        assertEquals(List.of("alpha"), sections);
        assertEquals(List.of("alpha"), unlocks);
        for (String id : ModuleDisableTest.REAL) {
            for (String action : List.of("menuPages", "passwordFieldActions", "settingsSections", "unlockProviders")) {
                assertTrue(id + ":" + action + " " + failures, failures.contains(id + ":" + action));
            }
        }
    }

    /** T-30：首頁溢位選單的模組頁面——擴充點丟例外的模組沒有項目；標題讀不到時用模組名稱、照樣有項目。 */
    @Test
    public void menuItemsSurviveFaults() {
        ModuleRegistry<AndroidFeature> registry = discover();
        List<String> titles = ModuleMenus.items(registry, res -> "頁面" + res).stream()
                .filter(i -> !ModuleDisableTest.REAL.contains(i.moduleId()))
                .map(i -> i.moduleId() + ":" + i.title() + ":" + i.pageClass()).collect(Collectors.toList());
        assertEquals(List.of("alpha:頁面1:androidx.fragment.app.Fragment", "beta:頁面1:androidx.fragment.app.Fragment"),
                titles);
        assertTrue(failures.toString(), failures.contains("faulty:menuPages"));

        failures.clear();
        List<String> fallback = ModuleMenus.items(registry, res -> {
            throw new IllegalStateException("找不到字串資源");
        }).stream().filter(i -> !ModuleDisableTest.REAL.contains(i.moduleId()))
                .map(i -> i.moduleId() + ":" + i.title()).collect(Collectors.toList());
        assertEquals(List.of("alpha:Alpha", "beta:Beta"), fallback);
        assertTrue(failures.toString(), failures.contains("alpha:menuTitle"));
    }

    /** T-35：停用通知丟例外的真模組（全部停用）被接住並回報，每個都收到通知、其他模組照常。 */
    @Test
    public void disabledNoticeFaultsAreContained() {
        List<AndroidFeature> modules = new ArrayList<>();
        for (AndroidFeature module : discover().all()) {
            if (ModuleDisableTest.REAL.contains(module.id())) {
                modules.add(new ThrowingAndroidFeature(module));
            }
        }
        failures.clear();
        ModuleGuard guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
        ModuleRegistry<AndroidFeature> registry = ModuleRegistry.of(modules, id -> Optional.of(false), guard);

        AndroidModules.notifyDisabled(registry, null);

        for (String id : ModuleDisableTest.REAL) {
            assertTrue(id + " " + failures, failures.contains(id + ":onDisabled"));
        }
        assertEquals(failures.toString(), ModuleDisableTest.REAL.size(), failures.size());
    }

    @Test
    public void realModulesAreHealthy() {
        ModuleRegistry<AndroidFeature> registry = discover();
        registry.collect("menuPages", AndroidFeature::menuPages);
        registry.collect("passwordFieldActions", AndroidFeature::passwordFieldActions);
        registry.collect("settingsSections", AndroidFeature::settingsSections);
        registry.collect("unlockProviders", AndroidFeature::unlockProviders);
        for (String id : ModuleDisableTest.REAL) {
            assertTrue(id, registry.isEnabled(id));
            assertFalse(failures.toString(), failures.stream().anyMatch(f -> f.startsWith(id + ":")));
        }
    }
}
