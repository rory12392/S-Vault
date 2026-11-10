package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.SvaultApplication;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 螢幕關閉時立即上鎖（T-18；AC-12-2）：App 在前景、背景秒數設成 1 小時，送 {@code KEYCODE_SLEEP} 關螢幕 → 上鎖。
 * 關螢幕後手機會停在鎖定畫面，之後的測試拿不到前景，所以一般的 {@code connectedDebugAndroidTest} 略過；
 * 要跑時加 {@code -e screenoff true}，跑完請使用者解鎖手機（R-31）。
 */
@RunWith(AndroidJUnit4.class)
public class ScreenOffLockTest {

    private SvaultApplication app;
    private TestVaultFiles files;
    private Settings before;

    @Before
    public void setUp() {
        if (!"true".equals(InstrumentationRegistry.getArguments().getString("screenoff"))) {
            // 一般的 connectedDebugAndroidTest 不跑（AGP 會把 Assume 的略過算成失敗，同 DriveReadProbeTest）
            return;
        }
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        before = app.settingsChanges().live().current();
    }

    @After
    public void tearDown() throws IOException {
        if (files == null) {
            return;
        }
        shell("input keyevent KEYCODE_WAKEUP");
        instr().runOnMainSync(() -> app.settingsChanges().change(s -> before, () -> { }));
        files.close();
    }

    @Test
    public void ac12_2_screenOffLocksImmediately() throws Exception {
        if (files == null) {
            return;
        }
        instr().runOnMainSync(() -> app.settingsChanges().change(
                s -> Settings.AUTO_LOCK_ANDROID_SECONDS.write(s, 3600), () -> { }));
        Vault v = new VaultCodec(TestVaults.fastParams()).create("關螢幕", TestVaults.password());
        new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "", ""));
        files.unlock(v, "關螢幕.kdbx");
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();

        shell("input keyevent KEYCODE_SLEEP");

        for (int i = 0; i < 100 && app.vaultSession().isUnlocked(); i++) {
            SystemClock.sleep(100);
        }
        assertTrue("螢幕關閉後上鎖", !app.vaultSession().isUnlocked());
    }

    /**
     * AC-05-4（T-19）：複製密碼後切到別的 App（背景設「立即」：上鎖但剪貼簿照計時，使用者決定），接著關螢幕 → 剪貼簿
     * 立即清除。關螢幕時 App 在背景、讀不到剪貼簿，所以看 S-Vault 有沒有還沒清的密碼（清除的條件見 AndroidClipboard）。
     */
    @Test
    public void ac05_4_screenOffClearsClipboardAfterBackgroundLock() throws Exception {
        if (files == null) {
            return;
        }
        instr().runOnMainSync(() -> app.settingsChanges().change(
                s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(Settings.AUTO_LOCK_ANDROID_SECONDS.write(s, 0), 300),
                () -> { }));
        Vault v = new VaultCodec(TestVaults.fastParams()).create("關螢幕", TestVaults.password());
        files.unlock(v, "關螢幕剪貼簿.kdbx");
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();
        instr().runOnMainSync(() -> app.clipboard().copySecret("gh-Demo-2026!"));
        instr().getContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (int i = 0; i < 100 && app.vaultSession().isUnlocked(); i++) {
            SystemClock.sleep(100);
        }
        assertTrue("切到背景立即上鎖", !app.vaultSession().isUnlocked());
        assertTrue("背景上鎖不清剪貼簿", pending());

        shell("input keyevent KEYCODE_SLEEP");

        for (int i = 0; i < 50 && pending(); i++) {
            SystemClock.sleep(100);
        }
        assertTrue("螢幕關閉後清除", !pending());
    }

    private boolean pending() {
        boolean[] p = new boolean[1];
        instr().runOnMainSync(() -> p[0] = app.clipboard().hasPendingSecret());
        return p[0];
    }

    private static void shell(String command) throws IOException {
        ParcelFileDescriptor out = instr().getUiAutomation().executeShellCommand(command);
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(out)) {
            // 讀完輸出：指令執行完才關閉（InputStream.readAllBytes 要 API 33）
            byte[] buf = new byte[256];
            while (in.read(buf) >= 0) {
                // 丟掉
            }
        }
    }
}
