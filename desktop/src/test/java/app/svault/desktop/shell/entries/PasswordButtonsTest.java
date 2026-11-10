package app.svault.desktop.shell.entries;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.desktop.shell.DesktopModules;
import app.svault.desktop.shell.ext.DesktopFeature;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 編輯畫面的 {@code PasswordFieldAction} 擴充點（測試的假模組 alpha、faulty）：停用的模組沒有按鈕，
 * 出錯的模組回傳空、通知外殼，不影響編輯。真的 GEN 也註冊了「產生」（T-41），這裡停用它，只看假模組。
 */
class PasswordButtonsTest {

    private static final String GENERATOR = "generator";

    @Test
    @DisplayName("每個啟用模組的動作一個按鈕；出錯的動作回傳空並通知外殼")
    void buttonsFromModules() {
        List<String> failures = new ArrayList<>();
        ModuleRegistry<DesktopFeature> registry = DesktopModules.discover(
                id -> id.equals(GENERATOR) ? Optional.of(false) : ModuleSettings.DEFAULTS.enabled(id),
                (id, action, error) -> failures.add(id + ":" + action));

        List<EntryEditPane.PasswordButton> buttons = PasswordButtons.from(registry);

        assertEquals(List.of("alpha-action", "faulty-action"),
                buttons.stream().map(EntryEditPane.PasswordButton::label).toList());
        assertArrayEquals(new char[] {'a'}, buttons.get(0).action().get().orElseThrow());
        assertEquals(Optional.empty(), buttons.get(1).action().get());
        assertTrue(failures.contains("faulty:passwordFieldAction"), failures.toString());
    }

    @Test
    @DisplayName("模組停用時沒有它的按鈕")
    void disabledModuleHasNoButton() {
        ModuleRegistry<DesktopFeature> registry = DesktopModules.discover(
                id -> id.equals("alpha") || id.equals(GENERATOR) ? Optional.of(false) : Optional.empty(),
                (id, action, error) -> { });

        assertEquals(List.of("faulty-action"),
                PasswordButtons.from(registry).stream().map(EntryEditPane.PasswordButton::label).toList());
    }
}
