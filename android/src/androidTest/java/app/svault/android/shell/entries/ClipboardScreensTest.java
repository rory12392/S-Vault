package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.start.UnlockActivity;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.time.Clock;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Android 剪貼簿（T-19；AC-05-1、AC-05-3、AC-05-4）在真的 App 裡走一遍：從詳細頁複製密碼 → 標成敏感、提示的秒數跟著
 * 設定 → 時間到清除；手動上鎖立即清除；切到背景而上鎖時不清（照計時，使用者決定）。關螢幕的清除在
 * {@link ScreenOffLockTest}（要另外指定才跑）。讀剪貼簿要 App 在前景，所以都在 S-Vault 的畫面上讀。假資料。
 */
@RunWith(AndroidJUnit4.class)
public class ClipboardScreensTest {

    private static final String SECRET = "gh-Demo-2026!";
    private SvaultApplication app;
    private TestVaultFiles files;
    private Settings before;
    private UUID entry;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        before = app.settingsChanges().live().current();
        settle();
        Vault v = new VaultCodec(TestVaults.fastParams()).create("剪貼簿測試", TestVaults.password());
        entry = new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", SECRET, "", ""));
        files.unlock(v, "剪貼簿測試.kdbx");
    }

    @After
    public void tearDown() {
        onMain(() -> {
            app.settingsChanges().change(s -> before, () -> { });
            app.clipboard().clearNow();
            return null;
        });
        files.close();
    }

    @Test
    public void ac05_1_ac05_3_copyMarksSensitiveAndClearsAfterSetting() {
        settings(10, 30);
        openDetail();

        copyPassword();

        ClipData clip = clip();
        assertEquals(SECRET, String.valueOf(clip.getItemAt(0).getText()));
        assertTrue("AC-05-3：標成敏感內容",
                clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
        assertTrue("AC-05-1：提示的秒數跟著設定",
                onMain(() -> hasText(resumed().getWindow().getDecorView(), "已複製密碼，10 秒後清除")));

        // 複製後到這裡約 1–2 秒（settle）
        SystemClock.sleep(6_000);
        assertEquals("還沒到 10 秒", SECRET, String.valueOf(clip().getItemAt(0).getText()));
        SystemClock.sleep(4_000);
        assertNull("AC-05-3：10 秒後清除", clipText());
    }

    @Test
    public void ac05_4_manualLockClearsImmediately() {
        settings(300, 30);
        openDetail();
        copyPassword();
        assertEquals(SECRET, String.valueOf(clip().getItemAt(0).getText()));

        onMain(() -> {
            app.lockNow();
            return null;
        });
        waitUntil(() -> resumed() instanceof UnlockActivity, "回到解鎖畫面");

        assertNull("AC-05-4：上鎖時立即清除", clipText());
    }

    @Test
    public void backgroundLockKeepsClipboardUntilTimer() {
        settings(10, 0);
        openDetail();
        copyPassword();

        // 切到別的 App 貼上：背景設「立即」→ 上鎖，但剪貼簿照計時（T-19 使用者決定）
        instr().getContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        for (int i = 0; i < 50 && app.vaultSession().isUnlocked(); i++) {
            SystemClock.sleep(100);
        }
        assertFalse("切到背景立即上鎖", app.vaultSession().isUnlocked());
        app.startActivity(UnlockActivity.lockedIntent(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitUntil(() -> resumed() instanceof UnlockActivity, "回到 S-Vault（讀剪貼簿要在前景）");
        assertEquals("背景上鎖不清", SECRET, String.valueOf(clip().getItemAt(0).getText()));

        SystemClock.sleep(10_500);
        assertNull("仍照設定的秒數清除", clipText());
    }

    // ---------- 工具 ----------

    private void settings(int clipboardSeconds, int backgroundSeconds) {
        onMain(() -> app.settingsChanges().change(s -> Settings.AUTO_LOCK_ANDROID_SECONDS.write(
                Settings.CLIPBOARD_CLEAR_SECONDS.write(s, clipboardSeconds), backgroundSeconds), () -> { }));
    }

    private void openDetail() {
        app.startActivity(EntryDetailActivity.intent(app, entry)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof EntryDetailActivity, "詳細頁");
    }

    private void copyPassword() {
        onMain(() -> resumed().findViewById(R.id.detail_password).findViewById(R.id.sv_field_copy).performClick());
        settle();
    }

    private ClipData clip() {
        ClipData c = onMain(() -> app.getSystemService(ClipboardManager.class).getPrimaryClip());
        assertTrue("剪貼簿有內容", c != null && c.getItemCount() > 0);
        return c;
    }

    /** @return 剪貼簿的文字；清除後為 {@code null} */
    private String clipText() {
        return onMain(() -> {
            ClipData c = app.getSystemService(ClipboardManager.class).getPrimaryClip();
            if (c == null || c.getItemCount() == 0 || c.getItemAt(0).getText() == null
                    || c.getItemAt(0).getText().length() == 0) {
                return null;
            }
            return c.getItemAt(0).getText().toString();
        });
    }

    private static boolean hasText(View v, String text) {
        if (v instanceof TextView t && text.contentEquals(t.getText())) {
            return true;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasText(g.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }
}
