package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.service.autofill.FillResponse;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry;
import androidx.test.runner.lifecycle.Stage;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.platform.DocumentAccess;
import app.svault.android.platform.SafVaultLocation;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.shell.start.UnlockActivity;
import app.svault.core.storage.RecentStore;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import com.google.android.material.textfield.TextInputLayout;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 上鎖時的驗證流程在手機上（T-84；AC-10-2，並確認 AC-10-3、AC-10-5）：驗證頁用正式程式的入口（{@link AuthPages#SHELL}
 * 同一組參數的 {@code ModuleHost.pageIntent}）開，上鎖時在同一個工作裡先開真的解鎖畫面，解鎖後回到驗證頁、重新比對並交回
 * {@code FillResponse}；返回、「開啟其他檔案」、顯示中被上鎖、解鎖後不符時都不交回帳密。
 *
 * <p>用 {@code ActivityScenario} 開頁面＝模擬系統代為開啟驗證項目（頁面在呼叫端的工作裡、結果回到呼叫端）。系統的建議清單與
 * 其他 App 的整段流程在實機人手驗證。保管庫放在假的 SAF 文件（{@link AfMemoryDocuments}），
 * 解鎖畫面才讀得到；先解鎖一次讓它成為最近開啟的第一筆（回程的解鎖畫面解鎖它）。
 */
@RunWith(AndroidJUnit4.class)
public class AutofillLockedFlowOnDeviceTest {

    private static final String URI = "content://app.svault.android.test.memory/document/t84.kdbx";
    private static final long WAIT_MS = 15_000;

    private SvaultApplication app;
    private DocumentAccess previousDocuments;
    private SafVaultLocation location;
    private UUID vaultId;
    private AutofillId user;
    private AutofillId pass;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        Vault v = new VaultCodec(TestVaults.fastParams()).create("上鎖填入", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, Clock.systemUTC());
        UUID root = v.groups().get(0).uuid();
        ed.addEntry(root, new EntryDraft("Discord", "discord-me", "dc-Demo-2026!", "androidapp://com.discord", ""));
        ed.addEntry(root, new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com/login", ""));
        AfMemoryDocuments docs = new AfMemoryDocuments();
        try (Vault opened = v) {
            docs.put(URI, new VaultCodec(TestVaults.fastParams()).serialize(opened));
            vaultId = opened.id();
        }
        previousDocuments = app.startActions().replaceDocuments(docs);
        location = SafVaultLocation.reopen(docs, URI, "上鎖填入.kdbx");
        app.vaultSession().lock();
        app.vaultSession().unlock(location, TestVaults.password());
        instr().waitForIdleSync();
        instr().runOnMainSync(() -> {
            user = new EditText(app).getAutofillId();
            pass = new EditText(app).getAutofillId();
        });
        lock();
    }

    @After
    public void tearDown() throws IOException {
        app.vaultSession().lock();
        instr().waitForIdleSync();
        app.startActions().replaceDocuments(previousDocuments);
        new RecentStore(app.localStore().recentFile()).remove(location.persistentId());
        deleteTree(app.localStore().backupsDir().resolve(vaultId.toString()));
        deleteTree(app.localStore().baseFile(vaultId, location.persistentId()));
        deleteTree(app.localStore().pendingFile(vaultId, location.persistentId()));
        deleteTree(app.localStore().conflictFile(vaultId, location.persistentId()));
    }

    /**
     * AC-10-2、AC-10-3：上鎖時選了驗證項目 → 同一個工作裡先叫出解鎖畫面（驗證頁還沒建立、不碰保管庫）→ 主密碼錯誤留在解鎖
     * 畫面 → 解鎖成功回到驗證頁，重新比對後交回符合的條目。
     */
    @Test
    public void ac10_2_lockedAuthPageOpensUnlockScreen() {
        assertTrue("正式程式要有驗證頁的入口",
                AuthPages.SHELL.intentSender(app, args("com.discord", null), 8401).isPresent());
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(args("com.discord", null)))) {
            awaitReturnUnlock(s);
            s.onActivity(a -> assertNull("解鎖前不建立驗證頁", a.findViewById(R.id.af_auth_loading)));

            type(R.id.unlock_password, "wrong-password");
            click(R.id.unlock_submit);
            waitUntil(() -> resumed() instanceof UnlockActivity && ((TextInputLayout) resumed()
                    .findViewById(R.id.unlock_password_layout)).getError() != null, "主密碼錯誤");
            assertTrue("主密碼錯誤時驗證頁還在等", s.getState() != Lifecycle.State.DESTROYED);

            type(R.id.unlock_password, "probe1234");
            click(R.id.unlock_submit);
            Instrumentation.ActivityResult result = s.getResult();
            assertEquals(Activity.RESULT_OK, result.getResultCode());
            FillResponse response = result.getResultData()
                    .getParcelableExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, FillResponse.class);
            assertNotNull(response);
            assertFalse(app.lockGate().isLocked());
            awaitDestroyed(s);
        }
    }

    /** AC-10-5：解鎖畫面按返回 → 驗證頁取消、不交回帳密，保管庫仍上鎖。 */
    @Test
    public void ac10_5_backOnUnlockGivesNothing() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(args("com.discord", null)))) {
            awaitReturnUnlock(s);
            onMain(() -> {
                ((ComponentActivity) resumed()).getOnBackPressedDispatcher().onBackPressed();
                return null;
            });
            assertNoCredentials(s.getResult());
            assertTrue(app.lockGate().isLocked());
            awaitDestroyed(s);
        }
    }

    /** AC-10-5：解鎖畫面改開其他保管庫（「開啟其他檔案」）→ 驗證頁取消、不交回帳密。 */
    @Test
    public void ac10_5_otherFileGivesNothing() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(args("com.discord", null)))) {
            awaitReturnUnlock(s);
            click(R.id.unlock_other);
            assertNoCredentials(s.getResult());
            awaitDestroyed(s);
        }
    }

    /** AC-10-5：上鎖時的請求解鎖後才比對，不符就顯示「沒有符合」；這時被上鎖也只結束驗證頁，都不交回帳密。 */
    @Test
    public void ac10_5_lockedMismatchGivesNothing() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(args("com.android.chrome", "evilgithub.com")))) {
            awaitReturnUnlock(s);
            type(R.id.unlock_password, "probe1234");
            click(R.id.unlock_submit);
            waitUntil(() -> resumed() instanceof FeatureHostActivity
                    && resumed().findViewById(R.id.af_auth_empty) != null
                    && resumed().findViewById(R.id.af_auth_empty).getVisibility() == View.VISIBLE, "沒有符合");
            onMain(() -> {
                app.lockNow();
                return null;
            });
            assertNoCredentials(s.getResult());
            awaitDestroyed(s);
        }
    }

    private AuthArgs args(String packageName, String domain) {
        return new AuthArgs(packageName, new FieldScan<>(List.of(user), List.of(pass), domain));
    }

    /** 同 {@link AuthPages#SHELL} 的參數，改成 Intent（{@code ActivityScenario} 開不了 IntentSender）。 */
    private Intent authIntent(AuthArgs args) {
        return ModuleHost.of(app).pageIntent(AutofillAndroidFeature.ID, R.string.af_auth_title, AfAuthFragment.class,
                args.toBundle()).orElseThrow();
    }

    private void lock() {
        onMain(() -> {
            app.lockNow();
            return null;
        });
        settle();
    }

    /** 解鎖畫面疊在驗證頁上、同一個工作裡，驗證頁還在（沒被結束、工作沒被清掉）。 */
    private static void awaitReturnUnlock(ActivityScenario<FeatureHostActivity> s) {
        waitUntil(() -> resumed() instanceof UnlockActivity
                && ((TextView) resumed().findViewById(R.id.unlock_name)).length() > 0, "回程的解鎖畫面");
        assertTrue("驗證頁不能被結束", s.getState() != Lifecycle.State.DESTROYED);
        int[] hostTask = new int[1];
        s.onActivity(a -> hostTask[0] = a.getTaskId());
        assertEquals("解鎖畫面要在同一個工作裡", hostTask[0], (int) onMain(() -> resumed().getTaskId()));
    }

    private static void assertNoCredentials(Instrumentation.ActivityResult result) {
        assertEquals(Activity.RESULT_CANCELED, result.getResultCode());
        Intent data = result.getResultData();
        assertFalse("不能交回帳密", data != null && data.hasExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT));
    }

    /** close() 對還沒結束的頁面會再 finish、把結果再序列化一次（P-AF-05），所以等它自己結束。 */
    private static void awaitDestroyed(ActivityScenario<FeatureHostActivity> s) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        while (s.getState() != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
        }
        assertEquals(Lifecycle.State.DESTROYED, s.getState());
    }

    private static void type(int id, String text) {
        onMain(() -> {
            ((TextView) resumed().findViewById(id)).setText(text);
            return null;
        });
        settle();
    }

    private static void click(int id) {
        onMain(() -> resumed().findViewById(id).performClick());
        settle();
    }

    /** 等主執行緒閒下來；螢幕不要關（關掉後 Activity 停止，畫面不更新）。 */
    private static void settle() {
        instr().waitForIdleSync();
        instr().runOnMainSync(() -> {
            for (Activity a : ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)) {
                a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        });
        SystemClock.sleep(500);
        instr().waitForIdleSync();
    }

    /** 主執行緒上呼叫時才有意義：前景的 Activity，沒有時為 null。 */
    private static Activity resumed() {
        Collection<Activity> a = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED);
        return a.isEmpty() ? null : a.iterator().next();
    }

    private static <T> T onMain(Supplier<T> get) {
        Object[] out = new Object[1];
        instr().runOnMainSync(() -> out[0] = get.get());
        @SuppressWarnings("unchecked")
        T t = (T) out[0];
        return t;
    }

    private static void waitUntil(Supplier<Boolean> done, String what) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        while (SystemClock.uptimeMillis() < end) {
            if (onMain(done)) {
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("等不到：" + what);
    }

    private static Instrumentation instr() {
        return InstrumentationRegistry.getInstrumentation();
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }
}
