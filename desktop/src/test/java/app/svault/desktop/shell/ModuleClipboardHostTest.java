package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.ClipboardPort;
import app.svault.core.shell.ModuleClipboards;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.desktop.shell.ext.ModuleHost;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * desktop {@link ModuleHost#clipboard()} 的接線（T-34）：每個模組的 host 拿到包著外殼同一個 {@link ClipboardGuard} 的
 * {@link ModuleClipboard}（規則在 core {@code ModuleClipboardsTest}）；沒接上剪貼簿的 hosts 多載與測試用的假 host 為空。
 * 正式程式的接線（{@code SvaultApp} 建立一個 {@code ClipboardGuard}，交給 hosts 與 {@code StartFlow}）用開發版
 * {@code --demo-modules} 的「複製假密碼」畫面腳本驗證（dev-log）。
 */
class ModuleClipboardHostTest {

    private final LiveSettings live = LiveSettings.inMemory(Settings.defaults());
    private final List<String> port = new ArrayList<>();
    private final List<Duration> scheduled = new ArrayList<>();
    private final List<Runnable> timers = new ArrayList<>();
    private final ClipboardGuard guard = new ClipboardGuard(new ClipboardPort() {
        @Override
        public void copy(String value, boolean sensitive) {
            port.add("copy:" + value + (sensitive ? ":sensitive" : ":plain"));
        }

        @Override
        public void clearSecret(String secret) {
            port.add("clear:" + secret);
        }
    }, (delay, task) -> {
        scheduled.add(delay);
        timers.add(task);
        return () -> timers.remove(task);
    }, live::current);
    private final List<String> asked = new ArrayList<>();
    private final Function<String, ModuleHost> hosts = DesktopModules.hosts(live, Runnable::run, id -> { },
            new ModuleVaultAccess(() -> { }), new ModuleVaultWriter(), id -> {
                asked.add(id);
                return ModuleClipboards.forModule(id, guard, () -> true);
            });

    @Test
    @DisplayName("T-34：模組複製密碼走外殼的 ClipboardGuard（秒數讀設定、標成敏感），上鎖時外殼的 clearNow 一起清")
    void hostClipboardSharesShellGuard() {
        live.update(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 45));
        ModuleClipboard generator = hosts.apply("generator").clipboard().orElseThrow();

        assertEquals(Duration.ofSeconds(45), generator.copySecret("Gen-Pass-1"));
        assertTrue(guard.hasPendingSecret());
        guard.clearNow();

        assertEquals(List.of("copy:Gen-Pass-1:sensitive", "clear:Gen-Pass-1"), port);
        assertEquals(List.of(Duration.ofSeconds(45)), scheduled);
        assertEquals(List.of(), timers);
    }

    @Test
    @DisplayName("T-34：每個模組的 host 建立時各問一次自己的代號；一般文字不標敏感、不排清除")
    void eachHostGetsItsOwnClipboard() {
        ModuleHost generator = hosts.apply("generator");
        ModuleHost health = hosts.apply("health");

        health.clipboard().orElseThrow().copy("user@example.com");

        assertEquals(List.of("generator", "health"), asked);
        assertTrue(generator.clipboard().isPresent());
        assertEquals(List.of("copy:user@example.com:plain"), port);
        assertEquals(List.of(), scheduled);
    }

    @Test
    @DisplayName("T-34：沒接上剪貼簿的 hosts 多載與只實作舊方法的假 host → 為空（模組不顯示「複製」）")
    void hostsWithoutClipboardAreEmpty() {
        ModuleHost legacy = DesktopModules.hosts(live, Runnable::run, id -> { },
                new ModuleVaultAccess(() -> { }), new ModuleVaultWriter()).apply("generator");
        ModuleHost oldest = DesktopModules.hosts(live, Runnable::run, id -> { }).apply("generator");
        ModuleHost fake = new ModuleHost() {
            @Override
            public ModulePreferences preferences() {
                return null;
            }

            @Override
            public EntryNavigator navigator() {
                return id -> { };
            }
        };

        assertFalse(legacy.clipboard().isPresent());
        assertFalse(oldest.clipboard().isPresent());
        assertFalse(fake.clipboard().isPresent());
    }
}
