package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.RecentStore;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 自動填入的填入流程在手機上（T-81；AC-10-2、AC-10-3、AC-10-5）：外殼給模組的唯讀保管庫（T-31 的 android 接線：解鎖後
 * 載入、比對、上鎖後先前拿到的物件失效）、上鎖時的驗證項目、驗證頁解鎖後重新比對並交回 {@code FillResponse}。
 *
 * <p>系統的自動填入服務要使用者在系統設定選 S-Vault，這裡不經過系統：直接以 {@code FeatureHostActivity} 開驗證頁、讀它
 * 交回的結果。建議清單本身在實機人手驗證。帶 {@code -e shots <前綴>} 時另外截「沒有符合」的畫面
 * （淺色／深色 × 預設與最大字體由 adb 切換後各跑一次），圖在 {@code files/shots/<前綴>/}。
 */
@RunWith(AndroidJUnit4.class)
public class AutofillFlowOnDeviceTest {

    private static final long WAIT_MS = 15_000;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:30:00Z"), ZoneId.of("Asia/Taipei"));
    private SvaultApplication app;
    private MemoryLocation location;
    private String vaultId;
    private AutofillId user;
    private AutofillId pass;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        Vault v = new VaultCodec(TestVaults.fastParams()).create("自動填入", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, clock);
        UUID root = v.groups().get(0).uuid();
        ed.addEntry(root, new EntryDraft("Discord", "discord-me", "dc-Demo-2026!", "androidapp://com.discord", ""));
        ed.addEntry(root, new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com/login", ""));
        app.vaultSession().lock();
        instr().waitForIdleSync();
        byte[] data;
        try (Vault vault = v) {
            data = new VaultCodec(TestVaults.fastParams()).serialize(vault);
            vaultId = vault.id().toString();
        }
        location = new MemoryLocation("自動填入.kdbx", data, false);
        app.vaultSession().unlock(location, TestVaults.password());
        instr().waitForIdleSync();
        instr().runOnMainSync(() -> {
            user = new EditText(app).getAutofillId();
            pass = new EditText(app).getAutofillId();
        });
    }

    @After
    public void tearDown() throws IOException {
        app.vaultSession().lock();
        instr().waitForIdleSync();
        new RecentStore(app.localStore().recentFile()).remove(location.persistentId());
        UUID id = UUID.fromString(vaultId);
        deleteTree(app.localStore().backupsDir().resolve(vaultId));
        deleteTree(app.localStore().baseFile(id, location.persistentId()));
        deleteTree(app.localStore().pendingFile(id, location.persistentId()));
        deleteTree(app.localStore().conflictFile(id, location.persistentId()));
    }

    /** T-31 的 android 接線：解鎖後內容載入好會通知、比對得到；上鎖後先前拿到的物件失效，服務照上鎖處理。 */
    @Test
    public void ac10_3_moduleHostGivesUnlockedEntriesUntilLocked() throws Exception {
        UnlockedEntries held = awaitEntries();
        assertEquals(List.of("Discord"), titles(held.autofill("com.discord", null)));
        assertEquals(List.of("GitHub"), titles(held.autofill("com.android.chrome", "github.com")));
        assertTrue(held.autofill("com.discord.fake", null).isEmpty());

        app.vaultSession().lock();
        instr().waitForIdleSync();
        assertFalse(held.isOpen());
        assertTrue(held.autofill("com.discord", null).isEmpty());
        assertFalse(ModuleHost.of(app).unlockedEntries().isPresent());
        FillPlan<AutofillId> plan = new FillPlanner(app.getPackageName(), new PackageSigners(app))
                .plan(fields(null), "com.discord", true, () -> Optional.of(held));
        assertEquals(FillPlan.Outcome.LOCKED, plan.outcome());
    }

    /** AC-10-2：上鎖時建得起「需要驗證」的回應（系統要的 IntentSender 是可修改的 PendingIntent）。 */
    @Test
    public void ac10_2_lockedResponseNeedsAuthentication() {
        FillPlan<AutofillId> locked = FillPlan.of(FillPlan.Outcome.LOCKED, fields(null));
        PendingIntent pi = PendingIntent.getActivity(app, 81, authIntent(new AuthArgs("com.discord", fields(null))),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        assertNotNull(FillResponses.locked(app, locked, pi.getIntentSender()));
        FillPlan<AutofillId> none = FillPlan.of(FillPlan.Outcome.LOCKED,
                new FieldScan<>(List.of(), List.of(), null));
        assertEquals(null, FillResponses.locked(app, none, pi.getIntentSender()));
        pi.cancel();
    }

    /** AC-10-2、AC-10-3：解鎖中開驗證頁，等內容載入後重新比對，把符合的條目交回系統。 */
    @Test
    public void ac10_2_authPageReturnsMatchingResponse() throws Exception {
        awaitEntries();
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(new AuthArgs("com.discord", fields(null))))) {
            Instrumentation.ActivityResult result = s.getResult();
            assertEquals(Activity.RESULT_OK, result.getResultCode());
            FillResponse response = result.getResultData()
                    .getParcelableExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, FillResponse.class);
            assertNotNull(response);
            awaitDestroyed(s);
        }
    }

    /**
     * T-110（D-197）：每筆建議不帶值、選了才開驗證頁。驗證頁帶條目代號時，仍符合就只交回那一筆帶值的項目（{@code Dataset}）；
     * 代號是不符這個請求的條目（例如另一個網站的）時不交回任何帳密。
     */
    @Test
    public void t110_entryPageReturnsOnlyThatEntry() throws Exception {
        UnlockedEntries entries = awaitEntries();
        String github = entries.autofill("com.android.chrome", "github.com").get(0).uuid().toString();
        String discord = entries.autofill("com.discord", null).get(0).uuid().toString();
        AuthArgs args = new AuthArgs("com.android.chrome", fields("github.com"));
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(args.forEntry(github)))) {
            Instrumentation.ActivityResult result = s.getResult();
            assertEquals(Activity.RESULT_OK, result.getResultCode());
            assertNotNull(result.getResultData()
                    .getParcelableExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, Dataset.class));
            awaitDestroyed(s);
        }
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(args.forEntry(discord)))) {
            waitFor(s, a -> a.findViewById(R.id.af_auth_empty).getVisibility() == View.VISIBLE);
            s.onActivity(a -> a.findViewById(R.id.sv_empty_action).performClick());
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
        }
    }

    /**
     * close() 會對還沒銷毀的 Activity 再 finish 一次、把結果再序列化一次；FillResponse 只能序列化一次（系統的流程只會有
     * 一次），所以等它自己結束。
     */
    private static void awaitDestroyed(ActivityScenario<FeatureHostActivity> s) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        while (s.getState() != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
        }
    }

    /** AC-10-5：解鎖後才比對，不符的請求不交回任何帳密，顯示說明與「關閉」。 */
    @Test
    public void ac10_5_authPageWithoutMatchGivesNothing() throws Exception {
        awaitEntries();
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(new AuthArgs("com.android.chrome", fields("evilgithub.com"))))) {
            waitFor(s, a -> a.findViewById(R.id.af_auth_empty).getVisibility() == View.VISIBLE);
            s.onActivity(a -> {
                assertEquals(View.GONE, a.findViewById(R.id.af_auth_loading).getVisibility());
                assertTrue(hasText(a.findViewById(R.id.af_auth_empty), app.getString(R.string.af_auth_none_title)));
            });
            shot(s, "81-auth-no-match");
            s.onActivity(a -> a.findViewById(R.id.sv_empty_action).performClick());
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
            assertFalse(s.getResult().getResultData() != null
                    && s.getResult().getResultData().hasExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT));
        }
    }

    /** 停用時驗證頁也不交回帳密。 */
    @Test
    public void disabledAuthPageGivesNothing() throws Exception {
        awaitEntries();
        instr().runOnMainSync(() -> ModuleHost.of(app).preferences(AutofillAndroidFeature.ID).put("enabled", Boolean.FALSE));
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                authIntent(new AuthArgs("com.discord", fields(null))))) {
            waitFor(s, a -> a.findViewById(R.id.af_auth_empty).getVisibility() == View.VISIBLE);
            s.onActivity(a -> a.findViewById(R.id.sv_empty_action).performClick());
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
        } finally {
            instr().runOnMainSync(() -> ModuleHost.of(app).preferences(AutofillAndroidFeature.ID).put("enabled", null));
        }
    }

    /** 參數不完整（不是本模組的驗證項目開的）時直接取消。 */
    @Test
    public void authPageWithoutArgsCancels() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                FeatureHostActivity.intent(app, AutofillAndroidFeature.ID, R.string.af_auth_title,
                        AfAuthFragment.class, null))) {
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
        }
    }

    // 上鎖時的整段流程（先解鎖、回到驗證頁交回結果）在 AutofillLockedFlowOnDeviceTest（T-84）

    private Intent authIntent(AuthArgs args) {
        return FeatureHostActivity.intent(app, AutofillAndroidFeature.ID, R.string.af_auth_title, AfAuthFragment.class,
                args.toBundle());
    }

    private FieldScan<AutofillId> fields(String domain) {
        return new FieldScan<>(List.of(user), List.of(pass), domain);
    }

    private UnlockedEntries awaitEntries() throws InterruptedException {
        CountDownLatch loaded = new CountDownLatch(1);
        assertTrue("已解鎖時應該會通知", ModuleHost.of(app).whenEntriesLoaded(Runnable::run, loaded::countDown));
        assertTrue("內容沒有載入", loaded.await(WAIT_MS, TimeUnit.MILLISECONDS));
        return ModuleHost.of(app).unlockedEntries().orElseThrow();
    }

    private static List<String> titles(List<EntryView> entries) {
        return entries.stream().map(EntryView::title).toList();
    }

    private interface Check {
        boolean test(FeatureHostActivity a);
    }

    private static void waitFor(ActivityScenario<FeatureHostActivity> s, Check check) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        boolean[] ok = new boolean[1];
        while (SystemClock.uptimeMillis() < end) {
            s.onActivity(a -> ok[0] = check.test(a));
            if (ok[0]) {
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("等不到畫面狀態");
    }

    private static boolean hasText(View v, String text) {
        if (v instanceof TextView t && text.contentEquals(t.getText())) {
            return true;
        }
        if (v instanceof android.view.ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasText(g.getChildAt(i), text)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 在 App 行程內把畫面畫成 PNG（FLAG_SECURE 的畫面用 adb 截會是全黑；同 shell 的 ScreenShots）。 */
    private void shot(ActivityScenario<FeatureHostActivity> s, String name) {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            return;
        }
        SystemClock.sleep(700);
        instr().waitForIdleSync();
        File dir = new File(app.getExternalFilesDir(null), "shots/" + prefix);
        s.onActivity(a -> {
            View root = a.getWindow().getDecorView();
            Bitmap out = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
            root.draw(new android.graphics.Canvas(out));
            dir.mkdirs();
            try (OutputStream o = new FileOutputStream(new File(dir, name + ".png"))) {
                out.compress(Bitmap.CompressFormat.PNG, 100, o);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
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
