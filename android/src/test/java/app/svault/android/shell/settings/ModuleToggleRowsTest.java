package app.svault.android.shell.settings;

import static org.junit.Assert.assertEquals;

import app.svault.android.R;
import app.svault.core.module.ModuleToggle;
import org.junit.Test;

/**
 * T-38：「功能模組」區段的提示——開關和這次啟動時的狀態不同才顯示，說明要重新開啟 App 後才生效。
 */
public class ModuleToggleRowsTest {

    @Test
    public void noteOnlyWhenRestartNeeded() {
        assertEquals(0, ModuleToggleRows.note(new ModuleToggle("biometric", "生物辨識", true, true)));
        assertEquals(0, ModuleToggleRows.note(new ModuleToggle("biometric", "生物辨識", false, false)));
        assertEquals(R.string.settings_modules_restart_off,
                ModuleToggleRows.note(new ModuleToggle("biometric", "生物辨識", true, false)));
        assertEquals(R.string.settings_modules_restart_on,
                ModuleToggleRows.note(new ModuleToggle("biometric", "生物辨識", false, true)));
    }
}
