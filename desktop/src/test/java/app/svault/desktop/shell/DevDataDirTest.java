package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 開發版不碰使用者真實的本機資料夾（STACK §2「使用者真實資料的保護」）。
 */
class DevDataDirTest {

    @Test
    @DisplayName("開發版沒設 SVAULT_DATA_DIR（系統屬性與環境變數都沒有或空白）時拒絕啟動")
    void devWithoutOverrideIsRefused() {
        assertTrue(DevDataDir.mustRefuse(true, null, null));
        assertTrue(DevDataDir.mustRefuse(true, " ", ""));
    }

    @Test
    @DisplayName("開發版有設 SVAULT_DATA_DIR（系統屬性或環境變數）時照常啟動")
    void devWithOverrideRuns() {
        assertFalse(DevDataDir.mustRefuse(true, "E:\\x\\.localdata\\data", null));
        assertFalse(DevDataDir.mustRefuse(true, null, "E:\\x\\.localdata\\data"));
    }

    @Test
    @DisplayName("正式版（不是開發模式）一律照常啟動，使用預設資料夾")
    void releaseAlwaysRuns() {
        assertFalse(DevDataDir.mustRefuse(false, null, null));
    }
}
