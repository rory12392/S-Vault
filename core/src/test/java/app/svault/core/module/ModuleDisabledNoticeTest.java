package app.svault.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 停用通知（T-35，BIO 停用時刪除金鑰）：{@link ModuleRegistry#forEachDisabled} 只走停用的模組、每個都在錯誤邊界內，
 * 一個模組丟例外不影響其他模組。
 */
class ModuleDisabledNoticeTest {

    private record Module(String id, boolean enabledByDefault) implements FeatureModule {
        @Override
        public String displayName() {
            return id;
        }
    }

    @Test
    @DisplayName("T-35：只通知停用的模組（設定停用與預設停用），啟用中的不通知，依探索順序")
    void onlyDisabledModules() {
        ModuleRegistry<Module> registry = ModuleRegistry.of(List.of(
                new Module("alpha", true), new Module("biometric", true), new Module("off", false),
                new Module("beta", true)),
                id -> id.equals("biometric") ? Optional.of(false) : Optional.empty(),
                new ModuleGuard(ModuleFailureListener.NONE));
        List<String> notified = new ArrayList<>();

        registry.forEachDisabled("onDisabled", m -> notified.add(m.id()));

        assertEquals(List.of("biometric", "off"), notified);
    }

    @Test
    @DisplayName("T-35：通知時丟例外的模組被接住並回報（模組代號、動作），其他模組照常收到")
    void faultIsContained() {
        List<String> failures = new ArrayList<>();
        ModuleRegistry<Module> registry = ModuleRegistry.of(List.of(
                new Module("faulty", false), new Module("biometric", false)),
                ModuleSettings.DEFAULTS,
                new ModuleGuard((id, action, error) -> failures.add(id + ":" + action + ":"
                        + error.getClass().getSimpleName())));
        List<String> notified = new ArrayList<>();

        registry.forEachDisabled("onDisabled", m -> {
            if (m.id().equals("faulty")) {
                throw new IllegalStateException("模組的錯誤");
            }
            notified.add(m.id());
        });

        assertEquals(List.of("biometric"), notified);
        assertEquals(List.of("faulty:onDisabled:IllegalStateException"), failures);
    }

    @Test
    @DisplayName("T-35：全部啟用時不呼叫")
    void noneDisabled() {
        ModuleRegistry<Module> registry = ModuleRegistry.of(List.of(new Module("alpha", true)),
                ModuleSettings.DEFAULTS, new ModuleGuard(ModuleFailureListener.NONE));
        List<String> notified = new ArrayList<>();

        registry.forEachDisabled("onDisabled", m -> notified.add(m.id()));

        assertEquals(List.of(), notified);
    }
}
