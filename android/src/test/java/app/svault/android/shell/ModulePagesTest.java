package app.svault.android.shell;

import static org.junit.Assert.assertThrows;

import androidx.fragment.app.Fragment;
import org.junit.Test;

/**
 * 模組開自己頁面的入口（T-36）在建立 Intent 之前就擋掉不合規則的呼叫：代號格式和設定、剪貼簿相同，頁面一定要有標題。
 * Intent 本身與上鎖的回程在儀器測試 {@code ModulePageUnlockTest}。
 */
public class ModulePagesTest {

    @Test
    public void rejectsBadModuleId() {
        for (String id : new String[] {null, "", "Auto-fill", "af.x"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ModulePages.intent(null, id, 1, Fragment.class, null));
            assertThrows(IllegalArgumentException.class,
                    () -> ModulePages.sender(null, id, 1, Fragment.class, null, 0));
        }
    }

    @Test
    public void requiresTitle() {
        assertThrows(IllegalArgumentException.class, () -> ModulePages.intent(null, "autofill", 0, Fragment.class, null));
    }
}
