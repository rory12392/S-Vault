package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.android.SvaultApplication;
import app.svault.android.platform.AndroidClipboard;
import app.svault.android.platform.ClipboardAlarm;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 剪貼簿的備援鬧鐘（T-28；AC-05-3）：App 行程被系統結束、主執行緒的計時跟著消失時，由 AlarmManager 叫起
 * {@code ClipboardClearReceiver} 清除。這裡不能真的結束自己的行程，所以直接排鬧鐘、不經主執行緒計時，看接收器
 * 單獨就能清；行程被結束的情況在實機上走（dev-log）。讀剪貼簿要 App 在前景，所以停在詳細頁上讀。假資料。
 */
@RunWith(AndroidJUnit4.class)
public class ClipboardAlarmScreensTest {

    private static final String SECRET = "gh-Demo-2026!";
    private SvaultApplication app;
    private TestVaultFiles files;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        settle();
        Vault v = new VaultCodec(TestVaults.fastParams()).create("備援鬧鐘測試", TestVaults.password());
        UUID entry = new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", SECRET, "", ""));
        files.unlock(v, "備援鬧鐘測試.kdbx");
        app.startActivity(EntryDetailActivity.intent(app, entry)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof EntryDetailActivity, "詳細頁");
    }

    @After
    public void tearDown() {
        onMain(() -> {
            app.clipboard().clearNow();
            ClipboardAlarm.cancel(app);
            return null;
        });
        files.close();
    }

    @Test
    public void ac05_3_alarmAloneClearsSvaultSecret() {
        onMain(() -> {
            new AndroidClipboard(app).copy(SECRET, true);
            ClipboardAlarm.schedule(app, Duration.ofSeconds(2));
            return null;
        });
        assertTrue("鬧鐘已排定", ClipboardAlarm.isScheduled(app));
        assertEquals(SECRET, clipText());

        waitLong(() -> clipText() == null, "接收器清除剪貼簿");
        assertFalse("清完後不再排定", ClipboardAlarm.isScheduled(app));
    }

    @Test
    public void alarmKeepsWhatAnotherAppCopied() {
        onMain(() -> {
            new AndroidClipboard(app).copy(SECRET, true);
            ClipboardAlarm.schedule(app, Duration.ofSeconds(2));
            // 使用者之後在別的 App 複製了東西（標籤不是 S-Vault）
            app.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("其他 App", "user-copied"));
            return null;
        });

        waitLong(() -> !ClipboardAlarm.isScheduled(app), "鬧鐘響過");
        SystemClock.sleep(500);
        assertEquals("別的 App 放的內容不清", "user-copied", clipText());
    }

    @Test
    public void guardSchedulesAndCancelsTheAlarm() {
        onMain(() -> app.clipboard().copySecret(SECRET));
        assertTrue("複製密碼時排定備援鬧鐘", ClipboardAlarm.isScheduled(app));

        onMain(() -> {
            app.clipboard().copy("rory-dev");
            return null;
        });
        assertFalse("改複製帳號後取消", ClipboardAlarm.isScheduled(app));

        onMain(() -> app.clipboard().copySecret(SECRET));
        assertTrue(ClipboardAlarm.isScheduled(app));
        onMain(() -> {
            app.clipboard().clearNow();
            return null;
        });
        assertFalse("上鎖立即清除時取消", ClipboardAlarm.isScheduled(app));
        assertEquals(null, clipText());
    }

    // 不精確的鬧鐘可能晚到：最多等 60 秒。done 在測試執行緒呼叫（clipText 自己會切到主執行緒）
    private static void waitLong(Supplier<Boolean> done, String what) {
        long end = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < end) {
            if (Boolean.TRUE.equals(done.get())) {
                return;
            }
            SystemClock.sleep(200);
        }
        throw new AssertionError("等不到：" + what);
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
}
