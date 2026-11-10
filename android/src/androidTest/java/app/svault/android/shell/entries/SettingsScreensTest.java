package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.back;
import static app.svault.android.shell.entries.ScreenShots.click;
import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.settings.AboutFragment;
import app.svault.android.shell.settings.SettingsChanges;
import app.svault.android.shell.settings.SettingsFragment;
import app.svault.core.shell.Notices;
import app.svault.core.storage.Settings;
import app.svault.core.storage.SettingsStore;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.File;
import java.time.Clock;
import java.util.List;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * android 設定與關於頁（T-24；US-12）在真的 App 裡走一遍：首頁溢位選單「設定」→ 背景上鎖、
 * 剪貼簿秒數、主題的選項（立即生效、寫進 settings.json）→「關於 S-Vault」→ 開源授權清單（core jar 裡的
 * NOTICES.md 複本在手機上讀得到）與展開全文。兩頁都在 {@code FeatureHostActivity}，要有 {@code FLAG_SECURE}。
 * 結束時把設定改回測試前的值。假資料保管庫（密碼 probe1234）放在記憶體位置。
 *
 * <p>一般的 {@code connectedDebugAndroidTest} 只做檢查；帶 {@code -e shots <前綴>} 時另外截圖（同 {@link EntryScreensTest}）。
 */
@RunWith(AndroidJUnit4.class)
public class SettingsScreensTest {

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
        // 從預設值開始；主題跟隨系統（截圖時由 cmd uimode 決定淺色深色）
        onMain(() -> changes().change(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(
                Settings.AUTO_LOCK_ANDROID_SECONDS.write(s, 30), 30).withTheme(Settings.THEME_SYSTEM), () -> { }));
    }

    @After
    public void tearDown() {
        onMain(() -> changes().change(s -> before, () -> { }));
        waitUntil(() -> stored().equals(before.autoLockAndroidSeconds() + "/" + before.clipboardClearSeconds() + "/"
                + before.theme()), "設定改回測試前的值");
        files.close();
    }

    @Test
    public void settingsAndAbout() throws Exception {
        files.unlock(vault(), "設定測試.kdbx");
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();

        // 首頁溢位選單「設定」→ FeatureHostActivity 裡的設定頁
        onMain(() -> ((MaterialToolbar) resumed().findViewById(R.id.page_toolbar)).getMenu()
                .performIdentifierAction(R.id.entries_settings, 0));
        waitUntil(() -> page() instanceof SettingsFragment, "設定頁開啟");
        assertSecure();
        assertEquals(app.getString(R.string.settings_title), onMain(() -> String.valueOf(
                ((MaterialToolbar) resumed().findViewById(R.id.sv_host_toolbar)).getTitle())));
        assertEquals("30 秒", value(R.id.settings_auto_lock_row));
        assertEquals("30 秒", value(R.id.settings_clipboard_row));
        // TalkBack：列可聚焦、區塊標題是標題
        assertTrue(onMain(() -> resumed().findViewById(R.id.settings_auto_lock_row).isFocusable()));
        assertTrue(onMain(() -> headingCount(resumed().findViewById(R.id.settings_body)) >= 4));
        shot("40-settings");

        // AC-12-2（設定的部分）：背景上鎖改成「立即」→ 立即生效、寫進 settings.json
        click(R.id.settings_auto_lock_row);
        shot("41-settings-auto-lock");
        clickText("立即");
        assertEquals(0, changes().current().autoLockAndroidSeconds());
        assertEquals("立即", value(R.id.settings_auto_lock_row));
        waitUntil(() -> stored().startsWith("0/"), "背景上鎖 0 寫進 settings.json");

        // AC-05（設定的部分）：剪貼簿改成 2 分鐘
        click(R.id.settings_clipboard_row);
        clickText("2 分鐘");
        assertEquals(120, changes().current().clipboardClearSeconds());
        assertEquals("2 分鐘", value(R.id.settings_clipboard_row));
        waitUntil(() -> stored().startsWith("0/120/"), "剪貼簿 120 寫進 settings.json");

        // 主題：深色 → 當場切換（Activity 重建後仍是設定頁、值保留）
        assertEquals("跟隨系統", value(R.id.settings_theme_row));
        click(R.id.settings_theme_row);
        clickText("深色");
        waitUntil(() -> AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
                && page() instanceof SettingsFragment && "深色".equals(valueOrNull(R.id.settings_theme_row)),
                "換成深色並重建設定頁");
        assertSecure();
        assertEquals("立即", value(R.id.settings_auto_lock_row));
        waitUntil(() -> stored().endsWith("/" + Settings.THEME_DARK), "主題寫進 settings.json");
        shot("42-settings-dark");
        click(R.id.settings_theme_row);
        clickText("跟隨系統");
        waitUntil(() -> AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                && page() instanceof SettingsFragment && "跟隨系統".equals(valueOrNull(R.id.settings_theme_row)),
                "主題改回跟隨系統");

        // 關於：core jar 的資源在手機上讀得到（T-23 提醒）
        List<Notices.Section> sections = Notices.forPlatform(Notices.Platform.ANDROID);
        assertFalse(sections.isEmpty());
        for (Notices.Section s : sections) {
            for (Notices.Item item : s.items()) {
                for (String id : item.licenseIds()) {
                    assertFalse(item.name() + " 的授權全文", Notices.licenseText(id).trim().isEmpty());
                }
            }
        }
        click(R.id.settings_about_row);
        waitUntil(() -> page() instanceof AboutFragment
                && ((ViewGroup) resumed().findViewById(R.id.about_licenses)).getChildCount() > 0, "關於頁列出授權");
        assertSecure();
        // 版本號照 App 實際的 versionName（v1.0 發佈時從 0.1.0 改成 1.0.0，這裡原本寫死，M-04 完整驗證時發現）
        String version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        assertEquals("版本 " + version, onMain(() -> text(R.id.about_version)));
        String firstName = sections.get(0).items().get(0).name();
        assertEquals(sections.get(0).title(), onMain(() -> ((TextView) ((ViewGroup) resumed()
                .findViewById(R.id.about_licenses)).getChildAt(0)).getText().toString()));
        View firstHeader = onMain(() -> ((ViewGroup) resumed().findViewById(R.id.about_licenses)).getChildAt(1)
                .findViewById(R.id.about_license_header));
        assertEquals("已收合", onMain(() -> String.valueOf(ViewCompat.getStateDescription(firstHeader))));
        shot("43-about");
        clickText(firstName);
        waitUntil(() -> {
            View row = ((ViewGroup) resumed().findViewById(R.id.about_licenses)).getChildAt(1);
            TextView full = row.findViewById(R.id.about_license_text);
            return full.getVisibility() == View.VISIBLE && full.length() > 100;
        }, "展開第一列並顯示授權全文");
        assertEquals("已展開", onMain(() -> String.valueOf(ViewCompat.getStateDescription(firstHeader))));
        assertEquals("「" + firstName + "」的授權全文", onMain(() -> String.valueOf(((ViewGroup) resumed()
                .findViewById(R.id.about_licenses)).getChildAt(1).findViewById(R.id.about_license_text)
                .getContentDescription())));
        shot("44-about-expanded");

        back();
        waitUntil(() -> page() instanceof SettingsFragment, "回到設定頁");
        // 上鎖：設定頁也導回解鎖畫面（SecureActivity）
        instr().runOnMainSync(() -> app.entries().lock());
        waitUntil(() -> !(resumed() instanceof FeatureHostActivity), "上鎖後離開設定頁");
    }

    private SettingsChanges changes() {
        return app.settingsChanges();
    }

    /** @return settings.json 裡的「背景秒數/剪貼簿秒數/主題」 */
    private String stored() {
        Settings s = new SettingsStore(app.localStore().settingsFile()).load();
        return s.autoLockAndroidSeconds() + "/" + s.clipboardClearSeconds() + "/" + s.theme();
    }

    private Vault vault() throws Exception {
        Vault v = new VaultCodec(TestVaults.fastParams()).create("設定測試", TestVaults.password());
        new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com/login", ""));
        return v;
    }

    private static Fragment page() {
        Activity a = resumed();
        if (!(a instanceof FeatureHostActivity)) {
            return null;
        }
        return ((FragmentActivity) a).getSupportFragmentManager().findFragmentById(R.id.sv_host_container);
    }

    private static void assertSecure() {
        assertTrue("設定與關於頁的視窗要有 FLAG_SECURE", onMain(() -> (resumed().getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SECURE) != 0));
    }

    private static String value(int rowId) {
        return onMain(() -> valueOrNull(rowId));
    }

    private static String valueOrNull(int rowId) {
        View row = resumed().findViewById(rowId);
        return row == null ? null : ((TextView) row.findViewById(R.id.settings_row_value)).getText().toString();
    }

    private static String text(int id) {
        return ((TextView) resumed().findViewById(id)).getText().toString();
    }

    private static int headingCount(View v) {
        int n = ViewCompat.isAccessibilityHeading(v) ? 1 : 0;
        if (v instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                n += headingCount(g.getChildAt(i));
            }
        }
        return n;
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
