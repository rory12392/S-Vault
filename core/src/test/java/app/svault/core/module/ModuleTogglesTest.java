package app.svault.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.Settings;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-38：設定頁「功能模組」區段的資料（{@link ModuleRegistry#toggles}）。停用中的模組也列出、只呼叫
 * {@code displayName()}（在錯誤邊界內），改了設定要重新啟動才生效；重新啟動（以新設定重新建立註冊表）後模組回來。
 */
class ModuleTogglesTest {

    /** 記下被呼叫的方法，確認停用的模組只被叫 {@code id}、{@code enabledByDefault}、{@code displayName}。 */
    private static final class Spy implements FeatureModule {
        final String id;
        final boolean byDefault;
        final boolean nameFails;
        final List<String> calls = new ArrayList<>();

        Spy(String id, boolean byDefault, boolean nameFails) {
            this.id = id;
            this.byDefault = byDefault;
            this.nameFails = nameFails;
        }

        @Override
        public String id() {
            calls.add("id");
            return id;
        }

        @Override
        public String displayName() {
            calls.add("displayName");
            if (nameFails) {
                throw new IllegalStateException("名稱壞了");
            }
            return id.toUpperCase(java.util.Locale.ROOT);
        }

        @Override
        public boolean enabledByDefault() {
            calls.add("enabledByDefault");
            return byDefault;
        }
    }

    private static ModuleRegistry<Spy> registry(List<Spy> modules, Settings settings, List<String> failures) {
        return ModuleRegistry.of(modules, settings,
                new ModuleGuard((id, action, error) -> failures.add(id + ":" + action)));
    }

    @Test
    @DisplayName("T-38：每個探索到的模組一列（停用的也列），依探索順序；名稱用 displayName")
    void listsEveryModuleIncludingDisabled() {
        List<Spy> modules = List.of(new Spy("alpha", true, false), new Spy("off", false, false),
                new Spy("beta", true, false));
        Settings settings = Settings.defaults().withModuleEnabled("beta", false);
        ModuleRegistry<Spy> registry = registry(modules, settings, new ArrayList<>());

        List<ModuleToggle> rows = registry.toggles(settings);

        assertEquals(List.of("alpha:ALPHA:true", "off:OFF:false", "beta:BETA:false"), rows.stream()
                .map(t -> t.id() + ":" + t.name() + ":" + t.enabled()).collect(Collectors.toList()));
        assertTrue(rows.stream().noneMatch(ModuleToggle::restartNeeded));
    }

    @Test
    @DisplayName("T-38：停用中的模組除了 id、enabledByDefault、displayName 之外不被呼叫")
    void disabledModuleOnlyAskedForItsName() {
        Spy off = new Spy("off", true, false);
        Settings settings = Settings.defaults().withModuleEnabled("off", false);
        ModuleRegistry<Spy> registry = registry(List.of(off), settings, new ArrayList<>());

        registry.toggles(settings);
        registry.collect("pages", m -> List.of("x"));

        assertEquals(List.of("id", "enabledByDefault", "displayName"), off.calls);
    }

    @Test
    @DisplayName("T-38：displayName 丟例外或空白時用模組代號，錯誤被接住並回報，其他列照常")
    void nameFailureFallsBackToId() {
        List<String> failures = new ArrayList<>();
        List<Spy> modules = List.of(new Spy("faulty", true, true), new Spy("alpha", true, false));
        ModuleRegistry<Spy> registry = registry(modules, Settings.defaults(), failures);

        List<ModuleToggle> rows = registry.toggles(Settings.defaults());

        assertEquals(List.of("faulty", "ALPHA"), rows.stream().map(ModuleToggle::name).collect(Collectors.toList()));
        assertEquals(List.of("faulty:displayName"), failures);
    }

    @Test
    @DisplayName("T-38：改了開關要重新啟動才生效——執行中的狀態不變，和設定不同時標示需要重新啟動，改回來就不用")
    void changeNeedsRestart() {
        Settings start = Settings.defaults();
        ModuleRegistry<Spy> registry = registry(List.of(new Spy("alpha", true, false)), start, new ArrayList<>());

        Settings off = start.withModuleEnabled("alpha", false);
        ModuleToggle row = registry.toggles(off).get(0);
        assertTrue(row.running());
        assertFalse(row.enabled());
        assertTrue(row.restartNeeded());
        assertTrue(registry.isEnabled("alpha"), "執行中不動態卸下");

        assertFalse(registry.toggles(off.withModuleEnabled("alpha", true)).get(0).restartNeeded());
    }

    @Test
    @DisplayName("T-38：停用後能從設定頁重新開啟——寫 modules.<id>.enabled=true，重新啟動後模組啟用、不再收到停用通知")
    void reenableAfterRestart() {
        List<Spy> modules = List.of(new Spy("alpha", true, false), new Spy("off", false, false));
        Settings disabled = Settings.defaults().withModuleEnabled("alpha", false);
        ModuleRegistry<Spy> before = registry(modules, disabled, new ArrayList<>());
        assertFalse(before.isEnabled("alpha"));
        assertFalse(before.isEnabled("off"));

        Settings saved = disabled.withModuleEnabled("alpha", true).withModuleEnabled("off", true);
        assertEquals(Boolean.TRUE, saved.bool("modules.alpha.enabled").orElse(null));
        ModuleRegistry<Spy> after = registry(modules, saved, new ArrayList<>());

        assertTrue(after.isEnabled("alpha"));
        assertTrue(after.isEnabled("off"), "預設停用的模組也能開啟");
        List<String> notified = new ArrayList<>();
        after.forEachDisabled("onDisabled", m -> notified.add(m.id));
        assertEquals(List.of(), notified);
        assertTrue(after.toggles(saved).stream().allMatch(t -> t.running() && t.enabled() && !t.restartNeeded()));
    }
}
