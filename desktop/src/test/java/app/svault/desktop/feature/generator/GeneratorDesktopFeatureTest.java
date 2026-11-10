package app.svault.desktop.feature.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.feature.generator.GeneratorSettings;
import app.svault.core.feature.generator.PasswordGenerator;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.MapPreferences;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.security.PasswordStrength;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.ClipboardPort;
import app.svault.core.shell.ModuleClipboards;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.desktop.shell.DesktopModules;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.ModuleHost;
import app.svault.desktop.shell.ext.PasswordFieldAction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * T-41：desktop 模組註冊的擴充點（建立畫面要 JavaFX，這裡只看註冊內容；畫面以截圖腳本確認）。T-43：獨立頁的「複製」接上
 * host 的剪貼簿。
 */
class GeneratorDesktopFeatureTest {

    @Test
    @DisplayName("US-04：註冊編輯畫面的「產生」與選單的「密碼產生器」")
    void registersActionAndPage() {
        GeneratorDesktopFeature feature = new GeneratorDesktopFeature();
        feature.attach(new ModuleHost() {
            @Override
            public ModulePreferences preferences() {
                return new MapPreferences();
            }

            @Override
            public EntryNavigator navigator() {
                return uuid -> { };
            }
        });
        assertEquals("generator", feature.id());
        assertEquals("密碼產生器", feature.displayName());
        List<PasswordFieldAction> actions = feature.passwordFieldActions();
        assertEquals(1, actions.size());
        assertEquals("產生", actions.get(0).label());
        List<MenuPage> pages = feature.menuPages();
        assertEquals(1, pages.size());
        assertEquals("密碼產生器", pages.get(0).title());
        assertEquals(List.of(), feature.settingsSections());
    }

    @Test
    @DisplayName("還沒 attach 就開產生器時丟例外（由外殼的 ModuleGuard 接住，顯示錯誤狀態）")
    void notAttachedFails() {
        GeneratorDesktopFeature feature = new GeneratorDesktopFeature();
        assertThrows(IllegalStateException.class, () -> feature.menuPages().get(0).factory().get());
    }

    @Test
    @DisplayName("AC-05-1、AC-05-4（T-43）：獨立頁的「複製」走 host 的剪貼簿＝外殼的 ClipboardGuard（標成敏感、秒數讀設定、上鎖時清）")
    void ac05_1_copyUsesHostClipboard() {
        LiveSettings live = LiveSettings.inMemory(Settings.defaults());
        live.update(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 45));
        List<String> port = new ArrayList<>();
        List<Duration> scheduled = new ArrayList<>();
        ClipboardGuard guard = new ClipboardGuard(new ClipboardPort() {
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
            return () -> { };
        }, live::current);
        GeneratorDesktopFeature feature = new GeneratorDesktopFeature();
        feature.attach(DesktopModules.hosts(live, Runnable::run, id -> { }, new ModuleVaultAccess(() -> { }),
                new ModuleVaultWriter(), id -> ModuleClipboards.forModule(id, guard, () -> true))
                .apply(GeneratorDesktopFeature.ID));

        ModuleClipboard copier = feature.copier().orElseThrow();
        GeneratorModel model = new GeneratorModel(GeneratorSettings.DEFAULTS, new PasswordGenerator(),
                pw -> PasswordStrength.STRONG, task -> { }, Runnable::run, s -> { });
        String shown = model.display();

        assertEquals(Optional.of(Duration.ofSeconds(45)), model.copy(copier));
        guard.clearNow();

        assertEquals(List.of("copy:" + shown + ":sensitive", "clear:" + shown), port);
        assertEquals(List.of(Duration.ofSeconds(45)), scheduled);
    }

    @Test
    @DisplayName("T-43：host 沒有剪貼簿（測試用的假 host）時不提供「複製」")
    void noHostClipboardNoCopy() {
        GeneratorDesktopFeature feature = new GeneratorDesktopFeature();
        feature.attach(new ModuleHost() {
            @Override
            public ModulePreferences preferences() {
                return new MapPreferences();
            }

            @Override
            public EntryNavigator navigator() {
                return uuid -> { };
            }
        });
        assertTrue(feature.copier().isEmpty());
    }
}
