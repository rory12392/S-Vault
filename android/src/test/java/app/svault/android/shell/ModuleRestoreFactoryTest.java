package app.svault.android.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.android.shell.settings.SettingsFragment;
import org.junit.Test;

/**
 * 重建畫面時模組的 Fragment 換成佔位（T-22）：外殼的頁面照常還原，其他（模組、測試、函式庫的 Fragment）都換掉。
 * 實際在 Activity 重建時的行為由儀器測試 {@code ModuleErrorScreensTest} 檢查。
 */
public class ModuleRestoreFactoryTest {

    @Test
    public void onlyShellPagesAreRestoredAsIs() {
        assertTrue(ModuleRestoreFactory.isShell(SettingsFragment.class.getName()));
        assertTrue(ModuleRestoreFactory.isShell(PendingModuleFragment.class.getName()));
        assertFalse(ModuleRestoreFactory.isShell("app.svault.android.feature.generator.GeneratorFragment"));
        assertFalse(ModuleRestoreFactory.isShell("app.svault.android.shellx.Evil"));
        assertFalse(ModuleRestoreFactory.isShell("com.google.android.material.datepicker.MaterialDatePicker"));
    }
}
