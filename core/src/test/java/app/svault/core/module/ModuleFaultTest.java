package app.svault.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.fake.FakeModules;
import app.svault.core.module.fake.TestFeature;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 故障注入測試：讓功能模組的進入點丟例外，程式不結束、其他模組照常、錯誤有記錄（Architecture §1.1）。
 */
class ModuleFaultTest {

    private final List<String> failures = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private final Logger guardLogger = Logger.getLogger(ModuleGuard.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            logLines.add(MessageFormat.format(r.getMessage(), r.getParameters()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private ModuleGuard guard;

    @BeforeEach
    void setUp() {
        guardLogger.addHandler(capture);
        guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
    }

    @AfterEach
    void tearDown() {
        guardLogger.removeHandler(capture);
    }

    private ModuleRegistry<TestFeature> discover() {
        return ModuleRegistry.discover(TestFeature.class, getClass().getClassLoader(), ModuleSettings.DEFAULTS, guard);
    }

    @Test
    @DisplayName("建構子失敗、代號重複的模組被略過並回報，其他模組照常載入")
    void discoveryFailuresAreIsolated() {
        ModuleRegistry<TestFeature> registry = discover();
        List<String> ids = registry.all().stream().map(FeatureModule::id).collect(Collectors.toList());
        assertFalse(ids.contains("broken"));
        assertEquals(1, ids.stream().filter("alpha"::equals).count());
        assertTrue(failures.contains("?:discover"), failures.toString());
        assertTrue(failures.contains("alpha:discover"), failures.toString());
    }

    @Test
    @DisplayName("擴充點丟例外或缺類別：略過該模組，其他模組的擴充照常，並通知外殼")
    void contributionFailuresAreIsolated() {
        List<String> pages = discover().collect("pages", TestFeature::pages).stream()
                .map(Contribution::value).collect(Collectors.toList());
        assertEquals(List.of("alpha-page", "beta-page-1", "beta-page-2"), pages);
        assertTrue(failures.contains("faulty:pages"), failures.toString());
        assertTrue(failures.contains("missing:pages"), failures.toString());
    }

    @Test
    @DisplayName("錯誤寫進日誌，但只記模組、動作與例外類型，不記例外訊息（可能含機密）")
    void failuresAreLoggedWithoutMessage() {
        discover().collect("pages", TestFeature::pages);
        assertTrue(logLines.stream().anyMatch(l -> l.contains("faulty") && l.contains("IllegalStateException")),
                logLines.toString());
        assertFalse(logLines.stream().anyMatch(l -> l.contains(FakeModules.SECRET)), logLines.toString());
    }

    @Test
    @DisplayName("ModuleGuard：例外與 LinkageError 被接住，VirtualMachineError 照常拋出")
    void guardBoundaries() {
        assertFalse(guard.run("x", "run", () -> {
            throw new IllegalArgumentException();
        }));
        assertEquals(Optional.empty(), guard.call("x", "call", () -> {
            throw new NoClassDefFoundError();
        }));
        assertEquals(Optional.of("ok"), guard.call("x", "call", () -> "ok"));
        assertThrows(OutOfMemoryError.class, () -> guard.call("x", "call", () -> {
            throw new OutOfMemoryError();
        }));
    }

    @Test
    @DisplayName("外殼的通知本身出錯時也不往上傳")
    void listenerFailureIsContained() {
        ModuleGuard noisy = new ModuleGuard((id, action, error) -> {
            throw new IllegalStateException("通知失敗");
        });
        assertFalse(noisy.run("x", "run", () -> {
            throw new IllegalArgumentException();
        }));
    }
}
