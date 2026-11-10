package app.svault.android.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.android.shell.ext.AndroidFeature;
import app.svault.core.module.Contribution;
import app.svault.core.module.FeatureModule;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.module.ModuleToggle;
import app.svault.core.storage.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * Android 的停用測試：逐一停用探索到的每個模組（APK 裡真的 GEN、HLT、IMP、BIO、AF 與測試的假模組），其他模組的擴充照常
 * （Architecture §1.1；T-22 起涵蓋真的模組）。
 */
public class ModuleDisableTest {

    /** Android 的真模組（META-INF/services，T-22）。 */
    static final Set<String> REAL = Set.of("generator", "health", "importer", "biometric", "autofill");

    private static List<String> owners(ModuleRegistry<AndroidFeature> registry) {
        List<String> out = new ArrayList<>();
        List<Function<AndroidFeature, List<?>>> points = List.of(AndroidFeature::menuPages,
                AndroidFeature::passwordFieldActions, AndroidFeature::settingsSections, AndroidFeature::unlockProviders);
        for (Function<AndroidFeature, List<?>> point : points) {
            registry.collect("point", point::apply).stream().map(Contribution::moduleId).forEach(out::add);
        }
        return out;
    }

    private static List<String> menuOwners(ModuleRegistry<AndroidFeature> registry) {
        return ModuleMenus.items(registry, res -> "t" + res).stream().map(ModuleMenus.Item::moduleId)
                .collect(Collectors.toList());
    }

    /** T-30：選單的模組頁面——停用 alpha 後只剩 beta 的項目（faulty 的擴充點丟例外，本來就沒有）。 */
    @Test
    public void disabledModuleHasNoMenuItem() {
        ModuleRegistry<AndroidFeature> all = AndroidModules.discover(ModuleSettings.DEFAULTS, ModuleFailureListener.NONE);
        ModuleRegistry<AndroidFeature> noAlpha = AndroidModules.discover(
                m -> m.equals("alpha") ? Optional.of(false) : Optional.empty(), ModuleFailureListener.NONE);
        List<String> fakes = menuOwners(all).stream().filter(m -> !REAL.contains(m)).collect(Collectors.toList());
        assertEquals(List.of("alpha", "beta"), fakes);
        assertEquals(List.of("beta"),
                menuOwners(noAlpha).stream().filter(m -> !REAL.contains(m)).collect(Collectors.toList()));
    }

    @Test
    public void realModulesAreDiscovered() {
        ModuleRegistry<AndroidFeature> all = AndroidModules.discover(ModuleSettings.DEFAULTS, ModuleFailureListener.NONE);
        Set<String> ids = all.all().stream().map(FeatureModule::id).collect(Collectors.toSet());
        assertEquals(Set.of("alpha", "beta", "faulty", "generator", "health", "importer", "biometric", "autofill"), ids);
        for (AndroidFeature module : all.all()) {
            if (REAL.contains(module.id())) {
                assertFalse(module.id(), module.displayName().trim().isEmpty());
                assertTrue(module.getClass().getName(),
                        module.getClass().getName().startsWith("app.svault.android.feature." + module.id() + "."));
                assertTrue(module.id(), all.isEnabled(module.id()));
            }
        }
    }

    @Test
    public void disableEachModule() {
        ModuleRegistry<AndroidFeature> all = AndroidModules.discover(ModuleSettings.DEFAULTS, ModuleFailureListener.NONE);
        List<String> ids = all.all().stream().map(FeatureModule::id).collect(Collectors.toList());
        assertTrue(ids.toString(), ids.containsAll(REAL));

        for (String id : ids) {
            ModuleRegistry<AndroidFeature> registry = AndroidModules.discover(
                    m -> m.equals(id) ? Optional.of(false) : Optional.empty(), ModuleFailureListener.NONE);
            assertFalse(registry.isEnabled(id));
            List<String> expected = owners(all).stream().filter(m -> !m.equals(id)).collect(Collectors.toList());
            assertEquals("停用 " + id + " 後", expected, owners(registry));
            // 首頁溢位選單（T-30）也一樣：停用的模組沒有項目
            assertEquals("停用 " + id + " 後的選單",
                    menuOwners(all).stream().filter(m -> !m.equals(id)).collect(Collectors.toList()),
                    menuOwners(registry));
            for (String other : ids) {
                if (!other.equals(id)) {
                    assertTrue("停用 " + id + " 後 " + other, registry.isEnabled(other));
                }
            }
        }
    }

    /**
     * T-38：逐一停用每個模組後，設定頁「功能模組」仍列出它（停用的模組只被叫 displayName）；從那裡開啟
     * （寫 {@code modules.<id>.enabled=true}）、重新啟動後擴充回來，也不再收到停用通知。
     */
    @Test
    public void reenableEachModuleFromSettings() {
        ModuleRegistry<AndroidFeature> all = AndroidModules.discover(ModuleSettings.DEFAULTS, ModuleFailureListener.NONE);
        List<String> ids = all.all().stream().map(FeatureModule::id).collect(Collectors.toList());

        for (String id : ids) {
            Settings disabled = Settings.defaults().withModuleEnabled(id, false);
            ModuleRegistry<AndroidFeature> registry = AndroidModules.discover(disabled, ModuleFailureListener.NONE);
            assertEquals("停用 " + id + " 後的功能模組區段", ids,
                    registry.toggles(disabled).stream().map(ModuleToggle::id).collect(Collectors.toList()));
            ModuleToggle row = registry.toggles(disabled).stream().filter(t -> t.id().equals(id)).findFirst()
                    .orElseThrow();
            assertFalse(id, row.enabled() || row.running());
            assertFalse(id, row.name().isEmpty());

            Settings reenabled = disabled.withModuleEnabled(id, true);
            assertTrue("重新啟動前 " + id, registry.toggles(reenabled).stream().filter(t -> t.id().equals(id))
                    .findFirst().orElseThrow().restartNeeded());
            ModuleRegistry<AndroidFeature> restarted = AndroidModules.discover(reenabled, ModuleFailureListener.NONE);
            assertTrue(id, restarted.isEnabled(id));
            assertEquals("重新開啟 " + id + " 後", owners(all), owners(restarted));
            List<String> notified = new ArrayList<>();
            restarted.forEachDisabled("onDisabled", m -> notified.add(m.id()));
            assertEquals(List.of(), notified);
        }
    }
}
