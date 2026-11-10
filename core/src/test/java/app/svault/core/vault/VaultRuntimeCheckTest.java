package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 執行環境自我檢查在 JVM 上全部通過；打包後與 Android 上的檢查分別由 --self-check 與 SmokeTest 負責。
 */
class VaultRuntimeCheckTest {

    @Test
    @DisplayName("JVM 上 Woodstox、Bouncy Castle、KeePassJava2 都載得到")
    void allPassOnJvm() {
        List<VaultRuntimeCheck.Result> results = VaultRuntimeCheck.run();
        assertEquals(6, results.size());
        assertTrue(VaultRuntimeCheck.allPassed(results), results.toString());
        assertTrue(results.get(0).detail().startsWith("com.ctc.wstx."), results.get(0).detail());
    }

    @Test
    @DisplayName("自我檢查會實際建立、序列化並重新開啟一個測試保管庫（Architecture §10）")
    void createsAndReopensVault() {
        VaultRuntimeCheck.Result last = VaultRuntimeCheck.run().get(4);
        assertEquals("保管庫（建立、存檔、重新開啟）", last.name());
        assertTrue(last.ok(), last.detail());
    }

    @Test
    @DisplayName("自我檢查實際評估一次密碼強度，確認 zxcvbn 的字典資源打包後讀得到（T-09）")
    void checksStrengthDictionaries() {
        VaultRuntimeCheck.Result strength = VaultRuntimeCheck.run().get(5);
        assertEquals("密碼強度（zxcvbn 字典）", strength.name());
        assertTrue(strength.ok(), strength.detail());
    }
}
