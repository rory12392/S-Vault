package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.back;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.ScreenShots.shot;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.shell.start.UnlockActivity;
import app.svault.android.testmodules.TestModules;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 首頁溢位選單的模組頁面與 {@code EntryNavigator}（T-30；Architecture §1.1、§6，DESIGN §4）：啟用的模組頁面依序排在
 * 「新增群組」與「備份還原」之間，停用的不出現；頁面建立失敗時是錯誤狀態；模組以 UUID 開條目詳細（一般、回收桶裡的、
 * 不存在的、其他執行緒呼叫、已上鎖）。APK 裡的真模組還沒註冊 {@code MenuPage}，所以換成測試用的模組。
 *
 * <p>帶 {@code -e shots <前綴>} 時另外截驗收圖（DESIGN §7：淺色／深色 × 預設與最大字體由 adb 切換後各跑一次）。
 */
@RunWith(AndroidJUnit4.class)
public class ModuleMenuScreensTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:30:00Z"), ZoneId.of("Asia/Taipei"));
    private final List<String> failures = new CopyOnWriteArrayList<>();
    private SvaultApplication app;
    private TestVaultFiles files;
    private UUID github;
    private UUID router;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        Vault v = new VaultCodec(TestVaults.fastParams()).create("模組選單", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, clock);
        UUID root = v.groups().get(0).uuid();
        UUID work = ed.addGroup(root, "工作");
        github = ed.addEntry(work, new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com", ""));
        router = ed.addEntry(root, new EntryDraft("舊的路由器", "admin", "admin", "http://192.168.1.1", ""));
        ed.recycleEntry(router);
        files.unlock(v, "模組選單.kdbx");
        List<AndroidFeature> modules = List.of(new TestModules.Paged(), new TestModules.PageCrashing(),
                new TestModules.PageDisabled());
        ModuleGuard guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
        onMain(() -> {
            app.replaceModules(ModuleRegistry.of(modules,
                    id -> id.equals(TestModules.PageDisabled.ID) ? Optional.of(false) : Optional.empty(), guard));
            return null;
        });
    }

    @After
    public void tearDown() {
        onMain(() -> {
            app.replaceModules(null);
            return null;
        });
        files.close();
    }

    @Test
    public void menuListsEnabledModulePages() {
        launchHome();
        // 上鎖按鈕之後依序：新增群組、模組頁面（啟用的）、備份還原、設定、關於（DESIGN §4）
        assertEquals(List.of(app.getString(R.string.home_lock), app.getString(R.string.entries_group_add),
                "測試頁面", "測試頁面出錯", app.getString(R.string.restore_menu), app.getString(R.string.settings_menu),
                app.getString(R.string.about_menu)), onMain(ModuleMenuScreensTest::visibleMenu));

        chooseMenu("測試頁面");
        waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT)
                && "測試頁面".contentEquals(toolbarTitle()), "模組頁面");
        back();
        waitUntil(() -> resumed() instanceof HomeActivity, "回到首頁");

        chooseMenu("測試頁面出錯");
        waitUntil(() -> resumed() instanceof FeatureHostActivity && errorShown(), "模組頁面的錯誤狀態");
        assertTrue(failures.toString(), failures.contains("testpagecrash:openPage"));
        back();
        waitUntil(() -> resumed() instanceof HomeActivity, "錯誤頁返回首頁");
    }

    @Test
    public void navigatorOpensEntries() {
        launchHome();
        chooseMenu("測試頁面");
        waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT), "模組頁面");

        // 一般條目：詳細疊在模組頁上，返回回到模組頁
        navigate(github);
        waitUntil(() -> resumed() instanceof EntryDetailActivity && "GitHub".contentEquals(detailTitle()),
                "GitHub 的詳細");
        back();
        waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT), "回到模組頁");

        // 回收桶裡的條目：一樣開，詳細顯示回收桶的狀態
        navigate(router);
        waitUntil(() -> resumed() instanceof EntryDetailActivity
                && resumed().findViewById(R.id.detail_in_bin).getVisibility() == View.VISIBLE, "回收桶裡的條目");
        back();
        waitUntil(() -> resumed() instanceof FeatureHostActivity, "回到模組頁");

        // 不存在的 UUID 與 null：不換畫面，顯示提示
        navigate(UUID.randomUUID());
        waitUntil(() -> resumed() instanceof FeatureHostActivity
                && hasText(app.getString(R.string.entries_toast_not_found)), "找不到的提示");
        navigate(null);
        settle();
        assertTrue(onMain(() -> resumed() instanceof FeatureHostActivity));

        // 其他執行緒呼叫：轉到主執行緒
        EntryNavigator fromHere = onMain(() -> ModuleHost.of(app).navigator(resumed()));
        fromHere.openEntry(github);
        waitUntil(() -> resumed() instanceof EntryDetailActivity && "GitHub".contentEquals(detailTitle()),
                "背景執行緒開的詳細");

        // 已上鎖：不做事（畫面導回解鎖），不丟例外
        onMain(() -> {
            app.lockNow();
            return null;
        });
        waitUntil(() -> resumed() instanceof UnlockActivity, "上鎖後的解鎖畫面");
        onMain(() -> {
            fromHere.openEntry(github);
            return null;
        });
        settle();
        assertTrue(onMain(() -> resumed() instanceof UnlockActivity));
    }

    @Test
    public void screens() {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            return;
        }
        File out = new File(app.getExternalFilesDir(null), "shots/" + prefix);
        launchHome();
        onMain(() -> ((MaterialToolbar) resumed().findViewById(R.id.page_toolbar)).showOverflowMenu());
        shot(out, "30-home-menu");
        onMain(() -> ((MaterialToolbar) resumed().findViewById(R.id.page_toolbar)).hideOverflowMenu());
        chooseMenu("測試頁面");
        waitUntil(() -> resumed() instanceof FeatureHostActivity && hasText(TestModules.OK_TEXT), "模組頁面");
        navigate(UUID.randomUUID());
        waitUntil(() -> hasText(app.getString(R.string.entries_toast_not_found)), "找不到的提示");
        shot(out, "31-module-page-not-found");
        navigate(router);
        waitUntil(() -> resumed() instanceof EntryDetailActivity, "回收桶裡的條目");
        shot(out, "32-navigated-recycled");
        back();
        back();
        waitUntil(() -> resumed() instanceof HomeActivity, "回到首頁");
        chooseMenu("測試頁面出錯");
        waitUntil(() -> resumed() instanceof FeatureHostActivity && errorShown(), "錯誤狀態");
        shot(out, "33-module-page-error");
    }

    private void launchHome() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof HomeActivity, "首頁");
    }

    private void navigate(UUID id) {
        onMain(() -> {
            ModuleHost.of(app).navigator(resumed()).openEntry(id);
            return null;
        });
    }

    /** 和使用者從溢位選單點一樣（選單項目交給 Toolbar 的 OnMenuItemClickListener）。 */
    private static void chooseMenu(String title) {
        onMain(() -> {
            Menu menu = ((MaterialToolbar) resumed().findViewById(R.id.page_toolbar)).getMenu();
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (item.isVisible() && title.contentEquals(item.getTitle())) {
                    return menu.performIdentifierAction(item.getItemId(), 0);
                }
            }
            throw new AssertionError("選單沒有：" + title);
        });
    }

    private static List<String> visibleMenu() {
        Menu menu = ((MaterialToolbar) resumed().findViewById(R.id.page_toolbar)).getMenu();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).isVisible()) {
                out.add(String.valueOf(menu.getItem(i).getTitle()));
            }
        }
        return out;
    }

    private static CharSequence toolbarTitle() {
        return ((MaterialToolbar) resumed().findViewById(R.id.sv_host_toolbar)).getTitle();
    }

    private static CharSequence detailTitle() {
        return ((MaterialToolbar) resumed().findViewById(R.id.detail_toolbar)).getTitle();
    }

    private static boolean errorShown() {
        Activity a = resumed();
        View error = a.findViewById(R.id.sv_host_error);
        return error != null && error.getVisibility() == View.VISIBLE
                && a.findViewById(R.id.sv_host_container).getVisibility() == View.GONE;
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
