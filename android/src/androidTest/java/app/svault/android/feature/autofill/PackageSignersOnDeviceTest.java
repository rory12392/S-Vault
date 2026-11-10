package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 實機上 App 的套件可見性（T-110；C-1、I-5、Codex B1）：{@code PackageSigners} 查不到憑證時不自動交出帳密，所以 manifest 的
 * {@code <queries>} 要讓瀏覽器（處理 https 網址）與一般 App（有桌面圖示）都看得到。測試在 App 的行程裡跑，用的是 App 自己的
 * 可見性。用 Pixel 內建的 Chrome 與計算機（不是瀏覽器、有桌面圖示）。
 */
@RunWith(AndroidJUnit4.class)
public class PackageSignersOnDeviceTest {

    private final Context context = ApplicationProvider.getApplicationContext();
    private final PackageSigners signers = new PackageSigners(context);

    @Test
    public void browserIsVisible() {
        Set<String> chrome = signers.of("com.android.chrome");
        assertFalse("看得到 Chrome 的簽署憑證（https 網址的 queries）", chrome.isEmpty());
        for (String s : chrome) {
            assertTrue("SHA-256 大寫十六進位：" + s, s.matches("[0-9A-F]{64}"));
        }
    }

    @Test
    public void launcherAppIsVisible() {
        assertFalse("看得到一般 App（計算機）的簽署憑證（LAUNCHER 的 queries）",
                signers.of("com.google.android.calculator").isEmpty());
    }

    @Test
    public void missingPackageHasNoSigners() {
        assertEquals(Set.of(), signers.of("app.svault.not.installed"));
    }
}
