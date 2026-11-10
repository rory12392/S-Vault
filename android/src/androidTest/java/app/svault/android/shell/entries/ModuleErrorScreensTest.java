package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.shell.settings.SettingsFragment;
import app.svault.android.testmodules.TestModules;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 功能模組在畫面上的錯誤狀態（T-22）：設定頁的模組段落出錯時只有那一段顯示「這個功能
 * 暫時無法使用」，其他段落照常；重建畫面（換主題、旋轉）時模組的 Fragment 不由 FragmentManager 還原，而是在錯誤邊界內
 * 重新建立（T-24 發現的已知限制）。模組頁面（{@code FeatureHostActivity}）同樣：重建時不結束 App，載入失敗的頁面重建後
 * 仍是錯誤狀態、不是空白頁。另外確認模組從 {@link ModuleHost} 讀寫自己的設定。
 *
 * <p>APK 裡的真模組都是空殼，所以換成測試用的模組（{@link TestModules}）；結束時換回來。
 */
@RunWith(AndroidJUnit4.class)
public class ModuleErrorScreensTest {

    private SvaultApplication app;
    private TestVaultFiles files;
    private final List<String> failures = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        // 設定頁與模組頁都在 SecureActivity 裡：沒有解鎖會導回解鎖畫面
        files.unlock(new VaultCodec(TestVaults.fastParams()).create("模組測試", TestVaults.password()), "模組測試.kdbx");
        List<AndroidFeature> modules = List.of(new TestModules.Healthy(), new TestModules.Crashing(),
                new TestModules.RestoreCrashing());
        ModuleGuard guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
        onMain(() -> {
            app.replaceModules(ModuleRegistry.of(modules, ModuleSettings.DEFAULTS, guard));
            return null;
        });
    }

    @After
    public void tearDown() {
        onMain(() -> {
            app.replaceModules(null);
            ModuleHost.of(app).preferences("testok").put("probe", null);
            return null;
        });
        files.close();
    }

    @Test
    public void settingsSectionsSurviveErrorsAndRecreate() {
        app.startActivity(FeatureHostActivity.intent(app, SettingsFragment.PAGE_ID, R.string.settings_title,
                SettingsFragment.class, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> page() instanceof SettingsFragment && hasText(TestModules.OK_TEXT), "設定頁與模組段落");
        assertSections();
        assertTrue(failures.toString(), failures.contains("testcrash:settingsSection"));

        // 重建（同換主題）：RestoreCrashFragment 若被 FragmentManager 以儲存的狀態還原就會丟例外、App 結束
        Activity before = onMain(ScreenShots::resumed);
        onMain(() -> {
            before.recreate();
            return null;
        });
        waitUntil(() -> resumed() != before && page() instanceof SettingsFragment
                && hasText(TestModules.OK_TEXT) && hasText(TestModules.RESTORE_TEXT), "重建後的設定頁");
        assertSections();
        assertFalse(failures.toString(), failures.contains("testrestore:settingsSection"));
    }

    @Test
    public void modulePagesSurviveRecreate() {
        // 正常的頁面：重建後換成新建立的實例（在錯誤邊界內），不是 FragmentManager 還原的
        app.startActivity(FeatureHostActivity.intent(app, "testrestore", R.string.settings_title,
                TestModules.RestoreCrashFragment.class, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> page() instanceof TestModules.RestoreCrashFragment && hasText(TestModules.RESTORE_TEXT),
                "模組頁面");
        Fragment first = onMain(ModuleErrorScreensTest::page);
        Activity before = onMain(ScreenShots::resumed);
        onMain(() -> {
            before.recreate();
            return null;
        });
        waitUntil(() -> resumed() != before && page() instanceof TestModules.RestoreCrashFragment
                && hasText(TestModules.RESTORE_TEXT), "重建後的模組頁面");
        assertNotSame(first, onMain(ModuleErrorScreensTest::page));

        // 載入失敗的頁面：錯誤狀態；重建後仍是錯誤狀態（以前重建後是空白頁）
        app.startActivity(FeatureHostActivity.intent(app, "testcrash", R.string.settings_title,
                TestModules.CrashFragment.class, null)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof FeatureHostActivity && errorShown(), "模組頁面的錯誤狀態");
        Activity failed = onMain(ScreenShots::resumed);
        onMain(() -> {
            failed.recreate();
            return null;
        });
        waitUntil(() -> resumed() != failed && resumed() instanceof FeatureHostActivity && errorShown(),
                "重建後仍是錯誤狀態");
    }

    @Test
    public void moduleReadsAndWritesOwnPreferences() {
        ModulePreferences prefs = ModuleHost.of(app).preferences("testok");
        onMain(() -> {
            prefs.put("probe", 7L);
            return null;
        });
        assertEquals(Optional.of(7L), prefs.number("probe"));
        assertEquals(Optional.of(7L), app.settingsChanges().current().number("modules.testok.probe"));
    }

    private static void assertSections() {
        String error = instr().getTargetContext().getString(R.string.settings_module_error);
        // 正常段落有內容；出錯的段落標題是模組名稱、內容是錯誤文字，其他段落不受影響
        assertTrue(onMain(() -> hasText("測試正常") && hasText("測試出錯") && hasText("測試重建")));
        assertTrue(onMain(() -> hasText(TestModules.OK_TEXT) && hasText(TestModules.RESTORE_TEXT)));
        assertEquals(1, (int) onMain(() -> count(resumed().getWindow().getDecorView(), error)));
    }

    private static Fragment page() {
        Activity a = resumed();
        if (!(a instanceof FeatureHostActivity)) {
            return null;
        }
        return ((FragmentActivity) a).getSupportFragmentManager().findFragmentById(R.id.sv_host_container);
    }

    private static boolean errorShown() {
        View error = resumed().findViewById(R.id.sv_host_error);
        return error != null && error.getVisibility() == View.VISIBLE
                && resumed().findViewById(R.id.sv_host_container).getVisibility() == View.GONE;
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
