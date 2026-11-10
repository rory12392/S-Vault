package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.TextView;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.settings.SettingsChanges;
import app.svault.android.shell.settings.SettingsFragment;
import app.svault.core.module.ModuleToggle;
import app.svault.core.storage.Settings;
import app.svault.core.storage.SettingsStore;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * T-38：設定頁「功能模組」區段在真的 App 裡走一遍。探索到的每個模組一個開關（依探索順序、停用中的也列）；
 * 關掉前 danger 確認，取消時開關與設定都不變；確認後寫 {@code modules.<id>.enabled=false} 並提示「完全關閉 S-Vault
 * 再開啟後停用」；再打開寫回 {@code true}、提示消失。執行中的模組不卸下（{@code ModuleRegistry} 不變）。
 * 結束時把設定改回測試前的值。假資料保管庫（密碼 probe1234）放在記憶體位置。
 *
 * <p>一般的 {@code connectedDebugAndroidTest} 只做檢查；帶 {@code -e shots <前綴>} 時另外截圖（同 {@link EntryScreensTest}）。
 */
@RunWith(AndroidJUnit4.class)
public class ModuleToggleScreensTest {

    /** 走一遍的模組：健檢（沒有停用通知要處理的資料）。 */
    private static final String MODULE = "health";

    private SvaultApplication app;
    private TestVaultFiles files;
    private File out;
    private Settings before;

    @Before
    public void setUp() {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        String prefix = InstrumentationRegistry.getArguments().getString("shots");
        out = prefix == null ? null : new File(app.getExternalFilesDir(null), "shots/" + prefix);
        before = changes().current();
    }

    @After
    public void tearDown() {
        onMain(() -> changes().change(s -> before, () -> { }));
        Optional<Boolean> wanted = before.enabled(MODULE);
        waitUntil(() -> stored().equals(wanted), "設定改回測試前的值");
        files.close();
    }

    @Test
    public void moduleTogglesListEveryModuleAndAskBeforeDisabling() throws Exception {
        assertTrue("測試 APK 要有健檢模組", app.modules().isEnabled(MODULE));
        files.unlock(vault(), "功能模組測試.kdbx");
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();
        onMain(() -> ((MaterialToolbar) resumed().findViewById(R.id.page_toolbar)).getMenu()
                .performIdentifierAction(R.id.entries_settings, 0));
        waitUntil(() -> page() instanceof SettingsFragment, "設定頁開啟");

        // 每個探索到的模組一列，依探索順序；開關的狀態＝設定裡的值；區塊標題是 TalkBack 的標題
        List<ModuleToggle> toggles = app.modules().toggles(changes().current());
        assertFalse(toggles.isEmpty());
        List<String> shown = new ArrayList<>();
        for (ModuleToggle t : toggles) {
            CompoundButton b = onMain(() -> switchOf(t.id()));
            shown.add(t.id());
            assertEquals(t.id(), t.name(), onMain(() -> b.getText().toString()));
            assertEquals(t.id(), t.enabled(), onMain(b::isChecked));
            assertTrue(onMain(b::isFocusable));
        }
        assertEquals(toggles.stream().map(ModuleToggle::id).collect(Collectors.toList()), shown);
        assertTrue(onMain(() -> hasHeading(resumed().findViewById(R.id.settings_body),
                app.getString(R.string.settings_section_modules))));
        CompoundButton health = onMain(() -> switchOf(MODULE));
        assertEquals(Integer.valueOf(View.GONE), onMain(() -> note(health).getVisibility()));
        scrollTo(health);
        shot("45-settings-modules");

        // 關掉 → danger 確認；取消時開關維持開啟、設定不變
        onMain(health::performClick);
        settle();
        shot("46-settings-modules-confirm");
        clickText(app.getString(R.string.sv_dialog_cancel));
        assertTrue(onMain(health::isChecked));
        assertEquals(before.enabled(MODULE), changes().current().enabled(MODULE));

        // 確認 → 寫 false、提示要完全關閉再開啟；執行中的模組不卸下
        onMain(health::performClick);
        settle();
        clickText(app.getString(R.string.settings_modules_disable_confirm));
        assertFalse(onMain(health::isChecked));
        assertEquals(Optional.of(false), changes().current().enabled(MODULE));
        waitUntil(() -> stored().equals(Optional.of(false)), "modules.health.enabled=false 寫進 settings.json");
        assertEquals(app.getString(R.string.settings_modules_restart_off), onMain(() -> note(health).getText()
                .toString()));
        assertTrue(app.modules().isEnabled(MODULE));
        shot("47-settings-modules-restart");

        // 再打開：不用確認，寫 true、提示消失
        onMain(health::performClick);
        settle();
        assertTrue(onMain(health::isChecked));
        waitUntil(() -> stored().equals(Optional.of(true)), "modules.health.enabled=true 寫進 settings.json");
        assertEquals(Integer.valueOf(View.GONE), onMain(() -> note(health).getVisibility()));

        instr().runOnMainSync(() -> app.entries().lock());
        waitUntil(() -> !(resumed() instanceof FeatureHostActivity), "上鎖後離開設定頁");
    }

    private SettingsChanges changes() {
        return app.settingsChanges();
    }

    /** @return settings.json 裡的 {@code modules.health.enabled} */
    private Optional<Boolean> stored() {
        return new SettingsStore(app.localStore().settingsFile()).load().enabled(MODULE);
    }

    private Vault vault() throws Exception {
        return new VaultCodec(TestVaults.fastParams()).create("功能模組測試", TestVaults.password());
    }

    private static CompoundButton switchOf(String moduleId) {
        View v = resumed().findViewById(R.id.settings_body).findViewWithTag("settings-module-" + moduleId);
        if (v == null) {
            throw new AssertionError("找不到模組開關：" + moduleId);
        }
        return (CompoundButton) v;
    }

    private static TextView note(CompoundButton button) {
        return ((View) button.getParent()).findViewById(R.id.settings_toggle_note);
    }

    private static void scrollTo(View v) {
        instr().runOnMainSync(() -> {
            android.graphics.Rect r = new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight());
            ((View) v.getParent()).requestRectangleOnScreen(r, true);
            v.requestRectangleOnScreen(r, true);
        });
        settle();
    }

    private static boolean hasHeading(View v, String title) {
        if (v instanceof TextView t && androidx.core.view.ViewCompat.isAccessibilityHeading(v)
                && title.contentEquals(t.getText())) {
            return true;
        }
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                if (hasHeading(g.getChildAt(i), title)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Fragment page() {
        if (!(resumed() instanceof FeatureHostActivity)) {
            return null;
        }
        return ((FragmentActivity) resumed()).getSupportFragmentManager().findFragmentById(R.id.sv_host_container);
    }

    private void shot(String name) {
        if (out != null) {
            ScreenShots.shot(out, name);
        }
    }

    private static <T> T onMain(Supplier<T> get) {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return get.get();
        }
        Object[] box = new Object[1];
        instr().runOnMainSync(() -> box[0] = get.get());
        @SuppressWarnings("unchecked")
        T t = (T) box[0];
        return t;
    }

    private static void waitUntil(Supplier<Boolean> done, String what) {
        for (int i = 0; i < 100; i++) {
            if (Boolean.TRUE.equals(onMain(() -> {
                try {
                    return done.get();
                } catch (RuntimeException | AssertionError e) {
                    return false;
                }
            }))) {
                settle();
                return;
            }
            SystemClock.sleep(100);
        }
        fail("等不到：" + what);
    }
}
