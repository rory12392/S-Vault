package app.svault.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.fake.TestFeature;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 停用測試：逐一停用 ServiceLoader 探索到的每個功能模組，其他模組的擴充照常（Architecture §1.1）。
 */
class ModuleDisableTest {

    private static ModuleRegistry<TestFeature> discover(ModuleSettings settings) {
        return ModuleRegistry.discover(TestFeature.class, ModuleDisableTest.class.getClassLoader(), settings,
                new ModuleGuard(ModuleFailureListener.NONE));
    }

    private static List<String> pages(ModuleRegistry<TestFeature> registry) {
        return registry.collect("pages", TestFeature::pages).stream()
                .map(Contribution::value).collect(Collectors.toList());
    }

    @Test
    @DisplayName("沒有設定時依 enabledByDefault：預設停用的模組不提供擴充")
    void defaults() {
        ModuleRegistry<TestFeature> registry = discover(ModuleSettings.DEFAULTS);
        assertEquals(List.of("alpha", "beta", "off", "faulty", "missing"),
                registry.all().stream().map(FeatureModule::id).collect(Collectors.toList()));
        assertFalse(registry.isEnabled("off"));
        assertEquals(List.of("alpha-page", "beta-page-1", "beta-page-2"), pages(registry));
    }

    @Test
    @DisplayName("逐一停用每個模組：該模組消失，其他模組的擴充照常")
    void disableEachModule() {
        List<String> baseline = pages(discover(ModuleSettings.DEFAULTS));
        for (TestFeature module : discover(ModuleSettings.DEFAULTS).all()) {
            String id = module.id();
            ModuleRegistry<TestFeature> registry =
                    discover(moduleId -> moduleId.equals(id) ? Optional.of(false) : Optional.empty());
            assertFalse(registry.isEnabled(id), id);
            assertFalse(registry.enabled().stream().anyMatch(m -> m.id().equals(id)), id);
            List<String> expected = baseline.stream()
                    .filter(page -> !page.startsWith(id + "-"))
                    .collect(Collectors.toList());
            assertEquals(expected, pages(registry), "停用 " + id + " 後");
        }
    }

    @Test
    @DisplayName("設定可以啟用預設停用的模組")
    void enableOffByDefault() {
        ModuleRegistry<TestFeature> registry =
                discover(id -> id.equals("off") ? Optional.of(true) : Optional.empty());
        assertTrue(registry.isEnabled("off"));
        assertTrue(pages(registry).contains("off-page"));
    }

    @Test
    @DisplayName("全部停用時沒有任何擴充，也不出錯")
    void disableAll() {
        ModuleRegistry<TestFeature> registry = discover(id -> Optional.of(false));
        assertTrue(registry.enabled().isEmpty());
        assertTrue(pages(registry).isEmpty());
    }
}
