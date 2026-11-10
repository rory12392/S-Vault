package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.back;
import static app.svault.android.shell.entries.ScreenShots.click;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.ScreenShots.shot;
import static app.svault.android.shell.entries.ScreenShots.type;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.platform.DocumentAccess;
import app.svault.android.platform.SafVaultLocation;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.shell.start.UnlockActivity;
import app.svault.android.shell.start.WelcomeActivity;
import app.svault.android.testmodules.TestModules;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import com.google.android.material.textfield.TextInputLayout;
import java.io.File;
import java.time.Clock;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 模組自己開的頁面與上鎖時的解鎖回程（T-36；支撐 AC-10-2 的「上鎖→解鎖→回填」，Architecture §1.1）：
 * {@code ModuleHost.pageIntent} 開的頁面遇到上鎖時，在同一個工作裡先開解鎖畫面，解鎖後回到頁面才載入 Fragment、頁面
 * 交回的結果照常傳給開它的畫面；返回、「開啟其他檔案」、顯示中被上鎖都只以 {@code RESULT_CANCELED} 結束頁面，不開首頁或
 * 歡迎畫面。{@code pageSender} 的 IntentSender 被送出端補上參數也換不掉頁面。
 *
 * <p>解鎖畫面是真的（走正式的解鎖流程）：保管庫放在假的 SAF 文件（{@link MemoryDocuments}），因為測試的文件提供者拿不到
 * 長期權限。用 {@code ActivityScenario} 開頁面＝模擬 AF 的系統代為開啟（頁面在呼叫端的工作裡、結果回到呼叫端）。
 * 帶 {@code -e shots <前綴>} 時另外截驗收圖（DESIGN §7：淺色／深色 × 預設與最大字體由 adb 切換後各跑一次）。
 */
@RunWith(AndroidJUnit4.class)
public class ModulePageUnlockTest {

    private static final String URI = "content://app.svault.android.test.memory/document/t36.kdbx";
    private static final String MODULE = "testpage";
    private static final long WAIT_MS = 10_000;

    private SvaultApplication app;
    private TestVaultFiles files;
    private DocumentAccess previousDocuments;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        Vault v = new VaultCodec(TestVaults.fastParams()).create("回程測試", TestVaults.password());
        new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com", ""));
        UUID vaultId = v.id();
        MemoryDocuments docs = new MemoryDocuments();
        try (Vault opened = v) {
            docs.put(URI, new VaultCodec(TestVaults.fastParams()).serialize(opened));
        }
        previousDocuments = app.startActions().replaceDocuments(docs);
        SafVaultLocation location = SafVaultLocation.reopen(docs, URI, "回程測試.kdbx");
        files.track(location, vaultId);
        // 解鎖一次：最近開啟的第一筆就是這個檔案（解鎖畫面的回程解鎖它）
        app.vaultSession().lock();
        app.vaultSession().unlock(location, TestVaults.password());
        instr().waitForIdleSync();
        settle();
    }

    @After
    public void tearDown() {
        files.close();
        app.startActions().replaceDocuments(previousDocuments);
    }

    @Test
    public void lockedPageUnlocksInSameTaskAndReturnsResult() {
        lock();
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(page())) {
            awaitReturnUnlock(s);
            assertFalse("解鎖前不建立模組的 Fragment", onMain(() -> hasText(TestModules.OK_TEXT)));

            // 主密碼錯誤：留在解鎖畫面
            type(R.id.unlock_password, "wrong-password");
            click(R.id.unlock_submit);
            waitUntil(() -> resumed() instanceof UnlockActivity
                    && ((TextInputLayout) resumed().findViewById(R.id.unlock_password_layout)).getError() != null,
                    "主密碼錯誤");

            type(R.id.unlock_password, "probe1234");
            click(R.id.unlock_submit);
            waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT),
                    "解鎖後回到頁面並載入");
            assertFalse(app.lockGate().isLocked());

            // 頁面交回結果（模組自己 setResult＋finish，同 AF 的驗證頁）
            onMain(() -> {
                Activity page = resumed();
                page.setResult(Activity.RESULT_OK, new Intent().putExtra("answer", 36));
                page.finish();
                return null;
            });
            Instrumentation.ActivityResult result = s.getResult();
            assertEquals(Activity.RESULT_OK, result.getResultCode());
            assertEquals(36, result.getResultData().getIntExtra("answer", 0));
            // 等頁面自己結束再關 scenario（不然 close() 會先疊空白畫面、再等 45 秒，同 T-81 的 P-AF-05）
            awaitDestroyed(s);
        }
    }

    @Test
    public void backOnUnlockCancelsPage() {
        lock();
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(page())) {
            awaitReturnUnlock(s);
            back();
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
            awaitDestroyed(s);
            assertNoShellScreen();
            assertTrue(app.lockGate().isLocked());
        }
    }

    @Test
    public void otherFileCancelsPage() {
        lock();
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(page())) {
            awaitReturnUnlock(s);
            click(R.id.unlock_other);
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
            awaitDestroyed(s);
            assertNoShellScreen();
        }
    }

    @Test
    public void lockWhileShownEndsOnlyThePage() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(page())) {
            waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT), "解鎖中直接載入");
            onMain(() -> {
                app.lockNow();
                return null;
            });
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
            awaitDestroyed(s);
            assertNoShellScreen();
        }
    }

    @Test
    public void senderOpensPageAndFillInCannotReplaceIt() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof HomeActivity, "首頁");
        IntentSender sender = ModuleHost.of(app).pageSender(MODULE, R.string.about_menu, TestModules.OkFragment.class,
                null, 3601).orElse(null);
        assertNotNull(sender);
        // 送出端補的參數（模擬系統或其他 App 拿到可修改的 PendingIntent）：頁面類別、標題、參數都蓋不掉
        Intent fillIn = new Intent()
                .putExtra("app.svault.android.extra.FRAGMENT", TestModules.CrashFragment.class.getName())
                .putExtra("app.svault.android.extra.TITLE_TEXT", "被換掉的標題")
                .putExtra("app.svault.android.extra.RETURN_AFTER_UNLOCK", false);
        onMain(() -> {
            try {
                resumed().startIntentSender(sender, fillIn, 0, 0, 0);
            } catch (IntentSender.SendIntentException e) {
                throw new AssertionError(e);
            }
            return null;
        });
        waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT), "IntentSender 開的頁面");
        assertTrue(onMain(() -> hasText(app.getString(R.string.about_menu))));
        // 回程沒被蓋掉：顯示中上鎖只結束頁面，下面的首頁照舊導回解鎖畫面
        onMain(() -> {
            app.lockNow();
            return null;
        });
        waitUntil(() -> resumed() instanceof UnlockActivity, "首頁上鎖後的解鎖畫面");
    }

    @Test
    public void rejectsBadModuleIdAndMissingTitle() {
        ModuleHost host = ModuleHost.of(app);
        assertThrows(() -> host.pageIntent("Bad-Id", R.string.about_menu, TestModules.OkFragment.class, null));
        assertThrows(() -> host.pageIntent(MODULE, 0, TestModules.OkFragment.class, null));
        assertThrows(() -> host.pageSender(null, R.string.about_menu, TestModules.OkFragment.class, null, 1));
    }

    @Test
    public void screens() {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            return;
        }
        File out = new File(app.getExternalFilesDir(null), "shots/" + prefix);
        lock();
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(page())) {
            waitUntil(() -> resumed() instanceof UnlockActivity
                    && ((TextView) resumed().findViewById(R.id.unlock_name)).length() > 0, "回程的解鎖畫面");
            shot(out, "36-return-unlock");
            type(R.id.unlock_password, "probe1234");
            click(R.id.unlock_submit);
            waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT), "回到頁面");
            shot(out, "37-return-page");
            onMain(() -> {
                resumed().finish();
                return null;
            });
            awaitDestroyed(s);
        }
    }

    private Intent page() {
        return ModuleHost.of(app).pageIntent(MODULE, R.string.about_menu, TestModules.OkFragment.class, null).get();
    }

    private void lock() {
        onMain(() -> {
            app.lockNow();
            return null;
        });
        instr().waitForIdleSync();
        settle();
    }

    /** 解鎖畫面疊在頁面上、同一個工作裡，頁面還在（沒有被結束、工作沒有被清掉）。 */
    private static void awaitReturnUnlock(ActivityScenario<FeatureHostActivity> s) {
        waitUntil(() -> resumed() instanceof UnlockActivity, "回程的解鎖畫面");
        assertTrue("頁面不能被結束", s.getState() != Lifecycle.State.DESTROYED);
        int[] hostTask = new int[1];
        boolean[] hostAlive = new boolean[1];
        s.onActivity(a -> {
            hostTask[0] = a.getTaskId();
            hostAlive[0] = !a.isFinishing();
        });
        assertTrue("頁面不能被結束", hostAlive[0]);
        assertEquals("解鎖畫面要在同一個工作裡", hostTask[0], (int) onMain(() -> resumed().getTaskId()));
    }

    /** 頁面結束後不能換成首頁、歡迎或（一般流程的）解鎖畫面：前景沒有 S-Vault 的畫面。 */
    private static void assertNoShellScreen() {
        settle();
        Activity front;
        try {
            front = onMain(ScreenShots::resumed);
        } catch (AssertionError | RuntimeException e) {
            return;
        }
        assertFalse("不該開 " + front.getClass().getSimpleName(), front instanceof HomeActivity
                || front instanceof WelcomeActivity || front instanceof UnlockActivity
                || front instanceof FeatureHostActivity);
    }

    private static void awaitDestroyed(ActivityScenario<FeatureHostActivity> s) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        while (s.getState() != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
        }
        assertEquals(Lifecycle.State.DESTROYED, s.getState());
    }

    private static void assertThrows(Runnable call) {
        try {
            call.run();
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError("應該丟 IllegalArgumentException");
    }

    private static boolean hasText(String text) {
        return count(resumed().getWindow().getDecorView(), text) > 0;
    }

    private static int count(View v, String text) {
        int n = v instanceof TextView t && v.isShown() && text.contentEquals(t.getText()) ? 1 : 0;
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                n += count(g.getChildAt(i), text);
            }
        }
        return n;
    }
}
