package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.ModuleClipboard;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.ClipboardPort;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模組的剪貼簿入口（T-34；支撐 AC-04 的複製、沿用 AC-05-1～AC-05-4）：模組複製密碼和外殼走同一個 {@link ClipboardGuard}，
 * 秒數、計時、上鎖時清除都相同；模組拿不到剪貼簿內容與 {@code ClipboardGuard} 本身，不能改秒數；不在 UI 執行緒時不複製。
 * {@code ClipboardGuard} 自己的規則由 {@code ClipboardGuardTest} 測。
 */
class ModuleClipboardsTest {

    private final FakePort port = new FakePort();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final LiveSettings live = LiveSettings.inMemory(Settings.defaults());
    private final ClipboardGuard guard = new ClipboardGuard(port, scheduler, live::current);
    private boolean uiThread = true;
    private final ModuleClipboard clipboard = ModuleClipboards.forModule("generator", guard, () -> uiThread);

    @Test
    @DisplayName("AC-05-1：模組複製密碼標成敏感內容，回傳設定的秒數（設定改了下一次就用新的）")
    void ac05_1_copySecretUsesShellPolicy() {
        assertEquals(Duration.ofSeconds(30), clipboard.copySecret("Gen-Pass-1"));
        live.update(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 10));
        assertEquals(Duration.ofSeconds(10), clipboard.copySecret("Gen-Pass-2"));

        assertEquals(List.of("copy:Gen-Pass-1:sensitive", "copy:Gen-Pass-2:sensitive"), port.calls);
        assertEquals(List.of(Duration.ofSeconds(30), Duration.ofSeconds(10)), scheduler.delays);
        assertTrue(guard.hasPendingSecret());
    }

    @Test
    @DisplayName("AC-05-2、AC-05-3：時間到了清除模組複製的密碼（條件由平台判斷）")
    void ac05_2_timerClearsModuleSecret() {
        clipboard.copySecret("Gen-Pass-1");

        scheduler.fireAll();

        assertEquals(List.of("copy:Gen-Pass-1:sensitive", "clear:Gen-Pass-1"), port.calls);
        assertFalse(guard.hasPendingSecret());
    }

    @Test
    @DisplayName("AC-05-4：上鎖時外殼的 clearNow 也清掉模組複製的密碼（同一個 ClipboardGuard）")
    void ac05_4_shellLockClearsModuleSecret() {
        clipboard.copySecret("Gen-Pass-1");

        guard.clearNow();
        scheduler.fireAll();

        assertEquals(List.of("copy:Gen-Pass-1:sensitive", "clear:Gen-Pass-1"), port.calls);
    }

    @Test
    @DisplayName("一般文字：不標成敏感、不排清除；取代外殼複製的密碼時取消外殼的清除（反過來也是）")
    void plainCopySharesShellState() {
        guard.copySecret("shell-secret");
        clipboard.copy("user@example.com");
        assertFalse(guard.hasPendingSecret());

        clipboard.copySecret("Gen-Pass-1");
        guard.copy("https://example.com");
        scheduler.fireAll();

        assertEquals(List.of("copy:shell-secret:sensitive", "copy:user@example.com:plain",
                "copy:Gen-Pass-1:sensitive", "copy:https://example.com:plain"), port.calls);
        assertEquals(2, scheduler.cancelled);
    }

    @Test
    @DisplayName("不在 UI 執行緒：丟 IllegalStateException，不放進剪貼簿、不排清除")
    void offUiThreadIsRejected() {
        uiThread = false;

        assertThrows(IllegalStateException.class, () -> clipboard.copySecret("Gen-Pass-1"));
        assertThrows(IllegalStateException.class, () -> clipboard.copy("text"));

        assertEquals(List.of(), port.calls);
        assertEquals(List.of(), scheduler.delays);
    }

    @Test
    @DisplayName("模組拿到的只有 copySecret 與 copy：讀不到內容、不能改秒數、拿不到 ClipboardGuard")
    void moduleSeesOnlyCopy() {
        List<String> methods = Arrays.stream(ModuleClipboard.class.getMethods()).map(Method::getName).sorted().toList();

        assertEquals(List.of("copy", "copySecret"), methods);
        assertFalse(ClipboardGuard.class.isInstance(clipboard));
        assertEquals(0, Arrays.stream(clipboard.getClass().getMethods())
                .filter(m -> m.getReturnType() == ClipboardGuard.class).count());
    }

    @Test
    @DisplayName("參數檢查：模組代號格式、null 的內容")
    void validatesArguments() {
        for (String bad : new String[] {null, "", "Generator", "gen.x"}) {
            assertThrows(IllegalArgumentException.class, () -> ModuleClipboards.forModule(bad, guard, () -> true));
        }
        assertThrows(NullPointerException.class, () -> clipboard.copySecret(null));
        assertThrows(NullPointerException.class, () -> clipboard.copy(null));
        assertEquals(List.of(), port.calls);
    }

    @Test
    @DisplayName("日誌只有模組代號，不含複製的值（層級 ALL）")
    void logsNoContent() {
        Logger logger = Logger.getLogger(ModuleClipboards.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level before = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(capture);
        try {
            clipboard.copySecret("Gen-Pass-1");
            clipboard.copy("user@example.com");
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(before);
        }

        assertEquals(2, records.size());
        for (LogRecord r : records) {
            String all = r.getMessage() + Arrays.toString(r.getParameters());
            assertTrue(all.contains("generator"), all);
            assertFalse(all.contains("Gen-Pass-1") || all.contains("user@example.com"), all);
        }
        assertFalse(clipboard.toString().contains("Gen-Pass-1"));
    }

    private static final class FakePort implements ClipboardPort {
        final List<String> calls = new ArrayList<>();

        @Override
        public void copy(String value, boolean sensitive) {
            calls.add("copy:" + value + (sensitive ? ":sensitive" : ":plain"));
        }

        @Override
        public void clearSecret(String secret) {
            calls.add("clear:" + secret);
        }
    }

    private static final class FakeScheduler implements ClipboardGuard.Scheduler {
        final List<Duration> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        int cancelled;

        @Override
        public Runnable schedule(Duration delay, Runnable task) {
            delays.add(delay);
            tasks.add(task);
            return () -> {
                if (tasks.remove(task)) {
                    cancelled++;
                }
            };
        }

        void fireAll() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            now.forEach(Runnable::run);
        }
    }
}
