package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.shell.settings.SettingsFragment;
import app.svault.core.module.ModulePreferences;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.RecentStore;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 啟用引導與設定段落在手機上（T-82；AC-10-1）：偵測結果和系統一致、引導頁記下看過、按鈕開的是系統的自動填入服務選單、
 * 關掉開關先確認。
 *
 * <p>系統的自動填入服務要使用者自己在系統設定選，測試不改它：依手機目前的狀態（{@code AutofillManager}）檢查畫面，兩種狀態
 * 各在實機跑一次。系統設定不真的打開：{@code ActivityMonitor} 攔下並記住 Intent。帶
 * {@code -e shots <前綴>} 時另外截引導頁與設定頁（淺色／深色 × 預設與最大字體由 adb 切換後各跑一次），圖在
 * {@code files/shots/<前綴>/}。
 */
@RunWith(AndroidJUnit4.class)
public class AutofillSetupOnDeviceTest {

    private static final long WAIT_MS = 15_000;

    private SvaultApplication app;
    private MemoryLocation location;
    private String vaultId;
    private ModulePreferences prefs;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        app.vaultSession().lock();
        instr().waitForIdleSync();
        byte[] data;
        try (Vault v = new VaultCodec(TestVaults.fastParams()).create("自動填入設定", TestVaults.password())) {
            data = new VaultCodec(TestVaults.fastParams()).serialize(v);
            vaultId = v.id().toString();
        }
        location = new MemoryLocation("自動填入設定.kdbx", data, false);
        // 模組頁面上鎖時會先開解鎖畫面（T-36）：先解鎖，從設定頁開的情況本來就是解鎖中
        app.vaultSession().unlock(location, TestVaults.password());
        instr().waitForIdleSync();
        prefs = ModuleHost.of(app).preferences(AutofillAndroidFeature.ID);
        instr().runOnMainSync(() -> {
            prefs.put(AutofillSetup.GUIDE_SEEN, Boolean.FALSE);
            prefs.put(AutofillSetup.ENABLED, Boolean.TRUE);
        });
    }

    @After
    public void tearDown() throws IOException {
        instr().runOnMainSync(() -> prefs.put(AutofillSetup.ENABLED, Boolean.TRUE));
        app.vaultSession().lock();
        instr().waitForIdleSync();
        new RecentStore(app.localStore().recentFile()).remove(location.persistentId());
        UUID id = UUID.fromString(vaultId);
        deleteTree(app.localStore().backupsDir().resolve(vaultId));
        deleteTree(app.localStore().baseFile(id, location.persistentId()));
        deleteTree(app.localStore().pendingFile(id, location.persistentId()));
        deleteTree(app.localStore().conflictFile(id, location.persistentId()));
    }

    /** AC-10-1：引導頁顯示的狀態和系統一致；還沒啟用時「前往系統設定」開系統的服務選單（指定本 App）；顯示過就記下。 */
    @Test
    public void ac10_1_guideShowsStatusAndOpensSystemSettings() {
        SetupState.Service service = AutofillSetup.service(app);
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launch(page(AfGuideFragment.class,
                R.string.af_guide_title))) {
            waitFor(s, a -> a.findViewById(R.id.af_status_text) != null);
            assertTrue("引導頁顯示過要記下", prefs.bool(AutofillSetup.GUIDE_SEEN).orElse(false));
            s.onActivity(a -> {
                assertEquals(app.getString(statusText(service)), text(a, R.id.af_status_text));
                assertEquals(service == SetupState.Service.NOT_ENABLED ? View.VISIBLE : View.GONE,
                        a.findViewById(R.id.af_guide_steps).getVisibility());
            });
            shot(s, "82-guide-" + service.name().toLowerCase());
            if (service != SetupState.Service.NOT_ENABLED) {
                return;
            }
            Capture system = capture(systemSettingsFilter());
            s.onActivity(a -> a.findViewById(R.id.af_guide_open).performClick());
            Intent sent = system.await();
            assertEquals("package:" + app.getPackageName(), sent.getDataString());
            instr().removeMonitor(system);
        }
    }

    /**
     * AC-10-1：設定頁的段落依狀態換主要按鈕——還沒看過引導時「設定自動填入」開引導頁，看過之後「前往系統設定」直接開系統的
     * 選單並多一個「自動填入說明」；已啟用時沒有主要按鈕。
     */
    @Test
    public void ac10_1_settingsSectionFollowsServiceAndGuide() {
        SetupState.Service service = AutofillSetup.service(app);
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launch(page(SettingsFragment.class,
                R.string.settings_title))) {
            waitFor(s, a -> findSection(a) != null && a.findViewById(R.id.af_status_text) != null);
            s.onActivity(a -> assertEquals(app.getString(statusText(service)), text(a, R.id.af_status_text)));
            // 段落在設定頁的最後：捲到看得見再截
            s.onActivity(a -> {
                View v = findSection(a).requireView();
                v.requestRectangleOnScreen(new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), true);
            });
            shot(s, "82-settings-" + service.name().toLowerCase());
            if (service != SetupState.Service.NOT_ENABLED) {
                s.onActivity(a -> {
                    assertEquals(View.GONE, a.findViewById(R.id.af_settings_primary).getVisibility());
                    assertEquals(View.VISIBLE, a.findViewById(R.id.af_settings_guide).getVisibility());
                });
                return;
            }
            s.onActivity(a -> {
                assertEquals(app.getString(R.string.af_settings_setup), text(a, R.id.af_settings_primary));
                assertEquals(View.GONE, a.findViewById(R.id.af_settings_guide).getVisibility());
            });
            Capture guide = capture(FeatureHostActivity.class.getName());
            s.onActivity(a -> a.findViewById(R.id.af_settings_primary).performClick());
            Intent opened = guide.await();
            instr().removeMonitor(guide);
            assertNotNull("要開引導頁", opened.getComponent());

            // 看過引導後回到設定頁（onResume 重新讀）
            instr().runOnMainSync(() -> AutofillSetup.markGuideSeen(app));
            s.moveToState(Lifecycle.State.STARTED);
            s.moveToState(Lifecycle.State.RESUMED);
            s.onActivity(a -> {
                assertEquals(app.getString(R.string.af_settings_open_system), text(a, R.id.af_settings_primary));
                assertEquals(View.VISIBLE, a.findViewById(R.id.af_settings_guide).getVisibility());
            });
            Capture system = capture(systemSettingsFilter());
            s.onActivity(a -> a.findViewById(R.id.af_settings_primary).performClick());
            assertEquals("package:" + app.getPackageName(), system.await().getDataString());
            instr().removeMonitor(system);
        }
    }

    /**
     * 開關只有外殼「功能模組」區段那一個（寫 {@code modules.autofill.enabled}，T-38）：段落裡只有說明，鍵一改服務立即照新值處理
     * （D-AF-06），不用等重新啟動。
     */
    @Test
    public void sectionPointsToModuleSwitchAndServiceFollowsEnabled() {
        try (ActivityScenario<FeatureHostActivity> s = ActivityScenario.launch(page(SettingsFragment.class,
                R.string.settings_title))) {
            waitFor(s, a -> findSection(a) != null && a.findViewById(R.id.af_settings_disable_note) != null);
            s.onActivity(a -> assertEquals(app.getString(R.string.af_settings_disable_note),
                    text(a, R.id.af_settings_disable_note)));
            assertTrue(AutofillAndroidFeature.enabled(app));
            instr().runOnMainSync(() -> prefs.put(AutofillSetup.ENABLED, Boolean.FALSE));
            assertFalse(AutofillAndroidFeature.enabled(app));
        }
    }

    private Intent page(Class<? extends Fragment> fragment, int titleRes) {
        return ModuleHost.of(app).pageIntent(AutofillAndroidFeature.ID, titleRes, fragment, null).orElseThrow()
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    private static int statusText(SetupState.Service service) {
        return switch (service) {
            case ENABLED -> R.string.af_status_enabled;
            case NOT_ENABLED -> R.string.af_status_off;
            default -> R.string.af_status_unsupported;
        };
    }

    private static IntentFilter systemSettingsFilter() {
        IntentFilter filter = new IntentFilter(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
        filter.addDataScheme("package");
        return filter;
    }

    /**
     * 攔下開啟的畫面（不真的開），記住 Intent。用無參數建構子：只有這樣系統才會呼叫 {@link #onStartActivity}，比對自己做。
     */
    private static final class Capture extends Instrumentation.ActivityMonitor {
        private final IntentFilter filter;
        private final String className;
        private volatile Intent intent;

        Capture(IntentFilter filter, String className) {
            this.filter = filter;
            this.className = className;
        }

        @Override
        public Instrumentation.ActivityResult onStartActivity(Intent sent) {
            if (filter != null ? filter.match(null, sent, false, "t-82") >= 0
                    : sent.getComponent() != null && sent.getComponent().getClassName().equals(className)) {
                intent = sent;
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
            return null;
        }

        Intent await() {
            long end = SystemClock.uptimeMillis() + WAIT_MS;
            while (intent == null && SystemClock.uptimeMillis() < end) {
                SystemClock.sleep(50);
            }
            assertNotNull("沒有開啟預期的畫面", intent);
            return intent;
        }
    }

    private static Capture capture(IntentFilter filter) {
        Capture c = new Capture(filter, null);
        instr().addMonitor(c);
        return c;
    }

    private static Capture capture(String className) {
        Capture c = new Capture(null, className);
        instr().addMonitor(c);
        return c;
    }

    private static AfSettingsFragment findSection(FeatureHostActivity a) {
        return find(a.getSupportFragmentManager());
    }

    private static AfSettingsFragment find(FragmentManager fm) {
        for (Fragment f : fm.getFragments()) {
            if (f instanceof AfSettingsFragment section && section.getView() != null) {
                return section;
            }
            AfSettingsFragment child = find(f.getChildFragmentManager());
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    private static String text(Activity a, int id) {
        return ((TextView) a.findViewById(id)).getText().toString();
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

    /** 在 App 行程內把畫面畫成 PNG（FLAG_SECURE 的畫面用 adb 截會是全黑；同 T-81）。對話框另畫它自己的視窗。 */
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
