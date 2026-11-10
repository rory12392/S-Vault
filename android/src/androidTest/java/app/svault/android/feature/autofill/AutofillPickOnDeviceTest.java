package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.SystemClock;
import android.service.autofill.Dataset;
import android.view.View;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.EntryRow;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.RecentStore;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 「搜尋保管庫」與條目選擇頁在手機上（T-83；AC-10-3、AC-10-5）：沒有符合時的回應、條目選擇頁列出可填的條目並能搜尋、
 * 挑了符合的直接交回 {@link Dataset}、挑了不符的先 danger 確認（取消不交回、確認才交回），瀏覽器沒有網域時一律警告。
 *
 * <p>不經過系統的自動填入：直接以 {@code FeatureHostActivity} 開條目選擇頁、讀它交回的結果（同 {@code AutofillFlowOnDeviceTest}）。
 * 建議清單本身與實際填入在實機人手驗證。帶 {@code -e shots <前綴>} 時另外截條目選擇頁與
 * 不符警告（淺色／深色 × 預設與最大字體由 adb 切換後各跑一次），圖在 {@code files/shots/<前綴>/}。
 */
@RunWith(AndroidJUnit4.class)
public class AutofillPickOnDeviceTest {

    private static final long WAIT_MS = 15_000;
    private static final String CHROME = "com.android.chrome";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-29T06:30:00Z"), ZoneId.of("Asia/Taipei"));
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
        ed.addEntry(root, new EntryDraft("Chrome 本身", "chrome-me", "cr-Demo-2026!", "androidapp://" + CHROME, ""));
        ed.addEntry(root, new EntryDraft("沒有網址", "plain-me", "pl-Demo-2026!", "", ""));
        ed.addEntry(root, new EntryDraft("只有備註", "", "", "https://notes.example", "備註"));
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
        awaitEntries();
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

    /** AC-10-3：沒有符合時建得起「搜尋保管庫」（要驗證的項目、沒有值）；不是 SEARCH_ONLY 或沒有欄位時不建。 */
    @Test
    public void ac10_3_searchOnlyResponseOffersSearchItem() {
        PendingIntent pi = PendingIntent.getActivity(app, 83, pickIntent(new AuthArgs("com.discord", fields(null))),
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        assertNotNull(FillResponses.searchOnly(app, FillPlan.of(FillPlan.Outcome.SEARCH_ONLY, fields(null)),
                pi.getIntentSender()));
        assertNull(FillResponses.searchOnly(app, FillPlan.of(FillPlan.Outcome.LOCKED, fields(null)),
                pi.getIntentSender()));
        assertNull(FillResponses.searchOnly(app, FillPlan.of(FillPlan.Outcome.SEARCH_ONLY,
                new FieldScan<>(List.of(), List.of(), null)), pi.getIntentSender()));
        pi.cancel();
        // 正式程式有條目選擇頁的入口
        assertTrue(AuthPages.PICK.intentSender(app, new AuthArgs("com.discord", fields(null)), 83).isPresent());
    }

    /** AC-10-3：列出有帳號或密碼的條目（沒有東西可填的不列），可以搜尋；找不到時 EmptyState。 */
    @Test
    public void ac10_3_pickListShowsFillableEntriesAndSearches() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                pickIntent(new AuthArgs("com.evaair.android", fields(null))))) {
            waitFor(s, a -> rowTitles(a).size() == 4);
            s.onActivity(a -> {
                // 順序照核心的名稱排序（中文排序規則），這裡只看有哪些：「只有備註」沒有東西可填，不列
                assertEquals(Set.of("Chrome 本身", "Discord", "GitHub", "沒有網址"), new HashSet<>(rowTitles(a)));
                assertTrue(hasText(a.findViewById(R.id.af_pick_source),
                        app.getString(R.string.af_pick_source, "com.evaair.android")));
            });
            shot(s, null, "83-pick");
            s.onActivity(a -> ((EditText) a.findViewById(R.id.af_pick_search)).setText("git"));
            waitFor(s, a -> rowTitles(a).equals(List.of("GitHub")));
            s.onActivity(a -> ((EditText) a.findViewById(R.id.af_pick_search)).setText("zzz"));
            waitFor(s, a -> a.findViewById(R.id.af_pick_empty).getVisibility() == View.VISIBLE);
            s.onActivity(a -> {
                assertTrue(hasText(a.findViewById(R.id.af_pick_empty), app.getString(R.string.af_pick_none_title)));
                assertFalse(a.isFinishing());
            });
        }
    }

    /** 挑了符合的條目：不警告，直接交回那一筆的 Dataset。 */
    @Test
    public void ac10_3_pickMatchingEntryReturnsDataset() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                pickIntent(new AuthArgs("com.discord", fields(null))))) {
            waitFor(s, a -> !rowTitles(a).isEmpty());
            s.onActivity(a -> row(a, "Discord").performClick());
            assertDataset(s.getResult());
            awaitDestroyed(s);
        }
    }

    /** AC-10-5：挑了不符的條目先警告；取消不交回、仍在這一頁；確認才交回。 */
    @Test
    public void ac10_5_pickMismatchWarnsBeforeFilling() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                pickIntent(new AuthArgs(CHROME, fields("gitlab.com"))))) {
            waitFor(s, a -> !rowTitles(a).isEmpty());
            s.onActivity(a -> row(a, "GitHub").performClick());
            waitFor(s, a -> page(a).warning != null && page(a).warning.isShowing());
            s.onActivity(a -> {
                assertTrue(hasText(page(a).warning.getWindow().getDecorView(),
                        app.getString(R.string.af_pick_warn_message, "github.com", "gitlab.com")));
                assertFalse("確認前不能結束（不能交回帳密）", a.isFinishing());
            });
            shot(s, page(s), "83-warning");
            s.onActivity(a -> page(a).warning.getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
            waitFor(s, a -> !page(a).warning.isShowing());
            s.onActivity(a -> assertFalse("取消後留在這一頁", a.isFinishing()));

            s.onActivity(a -> row(a, "GitHub").performClick());
            waitFor(s, a -> page(a).warning.isShowing());
            s.onActivity(a -> page(a).warning.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
            assertDataset(s.getResult());
            awaitDestroyed(s);
        }
    }

    /** AC-10-5：瀏覽器沒有網域時一律不符——連 androidapp://<瀏覽器> 的條目也要先警告；返回不交回任何東西。 */
    @Test
    public void ac10_5_browserWithoutDomainAlwaysWarns() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                pickIntent(new AuthArgs(CHROME, fields(null))))) {
            waitFor(s, a -> !rowTitles(a).isEmpty());
            s.onActivity(a -> row(a, "Chrome 本身").performClick());
            waitFor(s, a -> page(a).warning != null && page(a).warning.isShowing());
            s.onActivity(a -> {
                assertTrue(hasText(page(a).warning.getWindow().getDecorView(),
                        app.getString(R.string.af_pick_warn_no_domain, CHROME)));
                page(a).warning.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
            });
            s.onActivity(a -> a.getOnBackPressedDispatcher().onBackPressed());
            Instrumentation.ActivityResult result = s.getResult();
            assertEquals(Activity.RESULT_CANCELED, result.getResultCode());
            assertFalse(result.getResultData() != null
                    && result.getResultData().hasExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT));
            awaitDestroyed(s);
        }
    }

    /** 沒有網址的條目：警告寫「沒有網址」；停用時與參數不完整時直接取消。 */
    @Test
    public void ac10_5_noUrlWarningAndDisabledOrMissingArgsCancel() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                pickIntent(new AuthArgs("com.discord", fields(null))))) {
            waitFor(s, a -> !rowTitles(a).isEmpty());
            s.onActivity(a -> row(a, "沒有網址").performClick());
            waitFor(s, a -> page(a).warning != null && page(a).warning.isShowing());
            s.onActivity(a -> {
                assertTrue(hasText(page(a).warning.getWindow().getDecorView(),
                        app.getString(R.string.af_pick_warn_no_url, "com.discord")));
                page(a).warning.dismiss();
            });
        }
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                FeatureHostActivity.intent(app, AutofillAndroidFeature.ID, R.string.af_pick_title,
                        AfPickFragment.class, null))) {
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
        }
        instr().runOnMainSync(() -> ModuleHost.of(app).preferences(AutofillAndroidFeature.ID).put("enabled", Boolean.FALSE));
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launchActivityForResult(
                pickIntent(new AuthArgs("com.discord", fields(null))))) {
            assertEquals(Activity.RESULT_CANCELED, s.getResult().getResultCode());
        } finally {
            instr().runOnMainSync(() -> ModuleHost.of(app).preferences(AutofillAndroidFeature.ID).put("enabled", null));
        }
    }

    private static void assertDataset(Instrumentation.ActivityResult result) {
        assertEquals(Activity.RESULT_OK, result.getResultCode());
        Dataset dataset = result.getResultData()
                .getParcelableExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, Dataset.class);
        assertNotNull(dataset);
    }

    /** 同 AutofillFlowOnDeviceTest：Dataset 只能序列化一次，等 Activity 自己結束（P-AF-05）。 */
    private static void awaitDestroyed(ActivityScenario<FeatureHostActivity> s) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        while (s.getState() != Lifecycle.State.DESTROYED && SystemClock.uptimeMillis() < end) {
            SystemClock.sleep(100);
        }
    }

    private Intent pickIntent(AuthArgs args) {
        return FeatureHostActivity.intent(app, AutofillAndroidFeature.ID, R.string.af_pick_title, AfPickFragment.class,
                args.toBundle());
    }

    private FieldScan<AutofillId> fields(String domain) {
        return new FieldScan<>(List.of(user), List.of(pass), domain);
    }

    private void awaitEntries() throws InterruptedException {
        CountDownLatch loaded = new CountDownLatch(1);
        assertTrue("已解鎖時應該會通知", ModuleHost.of(app).whenEntriesLoaded(Runnable::run, loaded::countDown));
        assertTrue("內容沒有載入", loaded.await(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    private static AfPickFragment page(FeatureHostActivity a) {
        for (Fragment f : a.getSupportFragmentManager().getFragments()) {
            if (f instanceof AfPickFragment p) {
                return p;
            }
        }
        throw new AssertionError("找不到條目選擇頁");
    }

    private static AfPickFragment page(ActivityScenario<FeatureHostActivity> s) {
        AfPickFragment[] out = new AfPickFragment[1];
        s.onActivity(a -> out[0] = page(a));
        return out[0];
    }

    private static List<String> rowTitles(FeatureHostActivity a) {
        RecyclerView list = a.findViewById(R.id.af_pick_list);
        List<String> out = new ArrayList<>();
        if (list == null || list.getVisibility() != View.VISIBLE) {
            return out;
        }
        for (int i = 0; i < list.getChildCount(); i++) {
            TextView title = list.getChildAt(i).findViewById(R.id.sv_entry_title);
            out.add(title.getText().toString());
        }
        return out;
    }

    private static EntryRow row(FeatureHostActivity a, String title) {
        RecyclerView list = a.findViewById(R.id.af_pick_list);
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            TextView t = child.findViewById(R.id.sv_entry_title);
            if (title.contentEquals(t.getText())) {
                return (EntryRow) child;
            }
        }
        throw new AssertionError("清單裡沒有 " + title);
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
        return AfPickScreens.hasText(v, text);
    }

    private static void shot(ActivityScenario<FeatureHostActivity> s, AfPickFragment withDialog, String name) {
        AfPickScreens.shot(s, withDialog == null ? null : withDialog.warning, name);
    }

    private static Instrumentation instr() {
        return InstrumentationRegistry.getInstrumentation();
    }

    private static void deleteTree(Path dir) throws IOException {
        AfPickScreens.deleteTree(dir);
    }
}
