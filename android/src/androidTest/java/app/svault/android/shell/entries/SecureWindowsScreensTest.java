package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.longClickText;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.rootViews;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.settings.AboutFragment;
import app.svault.android.shell.settings.SettingsFragment;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 防截圖（AC-12-4，T-19）：逐一打開每個顯示保管庫內容的畫面與它會開的對話框、選單，檢查 App 在螢幕上的<b>每一個視窗</b>
 * 都有 {@code FLAG_SECURE}（對話框是另一個視窗，不繼承 Activity 的設定）；彈出選單是 Activity 視窗的子視窗，跟著上層
 * 視窗保護，這裡確認它的上層有設定。衝突對照畫面在 {@code ConflictScreensTest}。畫面分類（哪些 Activity 刻意不設）在
 * JVM 的 {@code SecureActivityManifestTest}。
 *
 * <p>{@code -e hold <秒>}：打開「重新命名群組」對話框後停住（{@code -e holdat menu}：群組選單開著時），讓人用
 * {@code adb exec-out screencap} 確認截到的是黑的。
 */
@RunWith(AndroidJUnit4.class)
public class SecureWindowsScreensTest {

    private SvaultApplication app;
    private TestVaultFiles files;
    private UUID entry;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        settle();
        Vault v = new VaultCodec(TestVaults.fastParams()).create("防截圖測試", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, Clock.systemUTC());
        UUID work = ed.addGroup(v.groups().get(0).uuid(), "工作群組");
        entry = ed.addEntry(work, new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "", ""));
        files.unlock(v, "防截圖測試.kdbx");
    }

    @After
    public void tearDown() {
        files.close();
    }

    @Test
    public void ac12_4_everyWindowShowingVaultContentIsSecure() {
        List<String> checked = new ArrayList<>();

        start(new Intent(app, HomeActivity.class));
        checked.add(assertSecure("首頁"));
        longClickText("工作群組");
        hold("menu");
        checked.add(assertSecure("群組選單（PopupMenu）"));
        clickText(app.getString(R.string.entries_group_rename));
        hold("dialog");
        checked.add(assertSecure("重新命名群組對話框"));
        dismiss();
        longClickText("工作群組");
        clickText(app.getString(R.string.entries_group_delete));
        checked.add(assertSecure("刪除群組確認"));
        dismiss();

        start(EntryListActivity.intent(app, Listing.Scope.all()));
        checked.add(assertSecure("條目清單"));

        start(EntryDetailActivity.intent(app, entry));
        checked.add(assertSecure("條目詳細"));
        onMain(() -> resumed().findViewById(R.id.detail_delete).performClick());
        settle();
        checked.add(assertSecure("刪除條目確認"));
        dismiss();

        start(EntryEditActivity.edit(app, entry));
        waitUntil(() -> ((EditText) resumed().findViewById(R.id.edit_name)).length() > 0, "編輯畫面");
        checked.add(assertSecure("條目編輯"));
        onMain(() -> {
            ((EditText) resumed().findViewById(R.id.edit_notes)).setText("改了");
            return null;
        });
        ScreenShots.back();
        checked.add(assertSecure("捨棄修改？"));
        dismiss();

        start(FeatureHostActivity.intent(app, SettingsFragment.PAGE_ID, R.string.settings_title,
                SettingsFragment.class, null));
        checked.add(assertSecure("設定"));
        onMain(() -> resumed().findViewById(R.id.settings_clipboard_row).performClick());
        settle();
        checked.add(assertSecure("設定的選項對話框"));
        dismiss();

        start(FeatureHostActivity.intent(app, AboutFragment.PAGE_ID, R.string.about_title, AboutFragment.class,
                null));
        checked.add(assertSecure("關於"));

        assertEquals(12, checked.size());
    }

    // ---------- 工具 ----------

    private void start(Intent intent) {
        app.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        Class<?> target = intent.getComponent() == null ? null : classOf(intent.getComponent().getClassName());
        waitUntil(() -> target == null || target.isInstance(resumed()), intent.getComponent().getShortClassName());
    }

    private static Class<?> classOf(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * App 在螢幕上的每個視窗：一般視窗（Activity、對話框）自己要有 {@code FLAG_SECURE}；子視窗（彈出選單）跟著上層。
     *
     * @return 畫面名稱（記在清單裡數）
     */
    private static String assertSecure(String where) {
        List<String> report = onMain(() -> {
            Activity front = resumed();
            boolean frontSecure = isSecure(front.getWindow().getAttributes());
            List<String> bad = new ArrayList<>();
            int windows = 0;
            for (View root : rootViews()) {
                if (!root.isAttachedToWindow() || root.getVisibility() != View.VISIBLE
                        || !(root.getLayoutParams() instanceof WindowManager.LayoutParams lp)) {
                    continue;
                }
                windows++;
                boolean sub = lp.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW
                        && lp.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW;
                if (sub ? !frontSecure : !isSecure(lp)) {
                    bad.add(root.getClass().getSimpleName() + "（type " + lp.type + "）");
                }
            }
            if (windows == 0 || !frontSecure) {
                bad.add("前景畫面 " + front.getClass().getSimpleName());
            }
            return bad;
        });
        assertTrue(where + "：沒有 FLAG_SECURE 的視窗 " + report, report.isEmpty());
        return where;
    }

    private static boolean isSecure(WindowManager.LayoutParams lp) {
        return (lp.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    private static void dismiss() {
        instr().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        settle();
    }

    /** @param at 停在哪裡（{@code -e holdat menu|dialog}，預設 dialog） */
    private static void hold(String at) {
        String s = InstrumentationRegistry.getArguments().getString("hold");
        String where = InstrumentationRegistry.getArguments().getString("holdat", "dialog");
        if (s != null && where.equals(at)) {
            SystemClock.sleep(Long.parseLong(s) * 1000);
        }
    }
}
