package app.svault.android;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.core.vault.VaultRuntimeCheck;
import java.io.File;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android 上的自我檢查（Architecture §10）：StAX（Woodstox 系統屬性）、Bouncy Castle、KeePassJava2 在手機上載得到，
 * 本機資料夾與日誌建立成功。T-04 起加入建立、開啟、存檔測試保管庫。
 */
@RunWith(AndroidJUnit4.class)
public class SmokeTest {

    @Test
    public void runtimeLibrariesLoad() {
        List<VaultRuntimeCheck.Result> results = VaultRuntimeCheck.run();
        assertTrue(results.toString(), VaultRuntimeCheck.allPassed(results));
    }

    @Test
    public void localStoreAndLogCreated() {
        SvaultApplication app = ApplicationProvider.getApplicationContext();
        assertNotNull("本機資料夾建立失敗", app.localStore());
        File log = app.localStore().logsDir().resolve("svault0.log").toFile();
        assertTrue(log.getAbsolutePath(), log.exists());
    }
}
