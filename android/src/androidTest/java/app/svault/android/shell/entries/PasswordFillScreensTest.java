package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.click;
import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.shot;
import static app.svault.android.shell.entries.ScreenShots.type;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.PasswordFill;
import app.svault.android.testmodules.FillModules;
import app.svault.android.ui.SecureWindows;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.File;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 編輯畫面密碼欄動作的非同步回填（T-37，D-170；android「產生」流程）：模組開自己的底部 sheet，
 * 按「使用這個密碼」才回填；同步的動作照舊；畫面重建、離開、上鎖後不回填；交出去的陣列一律清零；模組出錯時編輯與
 * 儲存照常。用測試模組（{@link FillModules}），真的 GEN 底部 sheet 是 T-44。
 *
 * <p>帶 {@code -e shots <前綴>} 時另外截圖（編輯畫面的按鈕、sheet、回填後），淺色／深色、字體大小由外部的 adb 切換。
 */
@RunWith(AndroidJUnit4.class)
public class PasswordFillScreensTest {

    private static final String OLD_PASSWORD = "old-pass-1";

    private SvaultApplication app;
    private TestVaultFiles files;
    private UUID entry;
    private File out;
    private final List<String> failures = new CopyOnWriteArrayList<>();

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        Vault v = new VaultCodec(TestVaults.fastParams()).create("回填測試", TestVaults.password());
        entry = new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", OLD_PASSWORD, "https://github.com/login", ""));
        files.unlock(v, "回填測試.kdbx");
        FillModules.reset();
        List<AndroidFeature> modules = List.of(new FillModules.AsyncSheet(), new FillModules.Sync(),
                new FillModules.Crashing(), new FillModules.NoLabel());
        ModuleGuard guard = new ModuleGuard((id, action, error) -> failures.add(id + ":" + action));
        onMain(() -> {
            app.replaceModules(ModuleRegistry.of(modules, ModuleSettings.DEFAULTS, guard));
            return null;
        });
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        out = prefix == null ? null : new File(app.getExternalFilesDir(null), "shots/" + prefix);
    }

    @After
    public void tearDown() {
        onMain(() -> {
            app.replaceModules(null);
            return null;
        });
        FillModules.reset();
        files.close();
    }

    @Test
    public void sheetFillsPasswordOnUse() {
        openEdit();
        // 文字資源是 0 的模組沒有按鈕、有記錄；其他三個照常
        assertEquals(3, (int) onMain(() -> actions().getChildCount()));
        assertTrue(failures.toString(), failures.contains(FillModules.NoLabel.ID + ":labelRes"));
        String paste = app.getString(android.R.string.paste);
        // TalkBack 讀到的是模組給的按鈕文字
        assertEquals(paste, onMain(() -> {
            AccessibilityNodeInfo info = actions().getChildAt(0).createAccessibilityNodeInfo();
            return String.valueOf(info.getText());
        }));
        snap("01-edit-actions");

        // 取消：不回填、沒有修改（返回不問「捨棄修改？」）
        clickText(paste);
        waitUntil(() -> sheet() != null && sheet().getDialog() != null && sheet().getDialog().isShowing(), "sheet");
        assertTrue(onMain(() -> SecureWindows.isSecure(sheet().getDialog().getWindow())));
        clickText(FillModules.CANCEL_TEXT);
        waitUntil(() -> sheet() == null, "sheet 關閉");
        assertEquals(OLD_PASSWORD, password());

        // 使用這個密碼：回填、陣列清零、強度條跟著更新
        clickText(paste);
        waitUntil(() -> sheet() != null && sheet().getDialog() != null && sheet().getDialog().isShowing(), "sheet");
        snap("02-sheet");
        clickText(FillModules.USE_TEXT);
        waitUntil(() -> sheet() == null, "sheet 關閉");
        assertEquals(FillModules.SHEET_VALUE, password());
        assertZeroed(FillModules.lastValue);
        snap("03-filled");

        // 儲存後回到詳細；之後舊的回填不再填入（已離開）
        PasswordFill stale = FillModules.lastFill;
        click(R.id.edit_save);
        waitUntil(() -> resumed() instanceof EntryDetailActivity, "儲存後的詳細");
        assertStaleRefused(stale);
        assertTrue(failures.toString(), failures.stream().noneMatch(f -> f.startsWith(FillModules.AsyncSheet.ID)));
    }

    @Test
    public void syncActionStillFills() {
        openEdit();
        clickText(app.getString(android.R.string.copy));
        assertEquals(FillModules.SYNC_VALUE, password());
        assertZeroed(FillModules.lastValue);
    }

    @Test
    public void recreateDropsSheetAndFill() {
        openEdit();
        clickText(app.getString(android.R.string.paste));
        waitUntil(() -> sheet() != null && sheet().getDialog() != null && sheet().getDialog().isShowing(), "sheet");
        PasswordFill stale = FillModules.lastFill;
        Activity before = onMain(ScreenShots::resumed);
        // 重建（同換主題、旋轉）：測試的 sheet 被 FragmentManager 還原就會丟例外、App 結束
        onMain(() -> {
            before.recreate();
            return null;
        });
        waitUntil(() -> resumed() != before && resumed() instanceof EntryEditActivity
                && OLD_PASSWORD.equals(shownPassword()), "重建後的編輯畫面");
        assertNull(onMain(this::sheet));
        assertTrue(onMain(() -> ((FragmentActivity) resumed()).getSupportFragmentManager().getFragments().stream()
                .noneMatch(f -> f.getClass().getName().endsWith("PendingModuleFragment"))));
        assertStaleRefused(stale);
        assertEquals(OLD_PASSWORD, password());
        // 新的畫面照常可以用
        clickText(app.getString(android.R.string.paste));
        waitUntil(() -> sheet() != null && sheet().getDialog() != null && sheet().getDialog().isShowing(), "sheet");
        clickText(FillModules.USE_TEXT);
        waitUntil(() -> FillModules.SHEET_VALUE.equals(shownPassword()), "重建後回填");
    }

    @Test
    public void lockedRefusesFill() {
        openEdit();
        clickText(app.getString(android.R.string.paste));
        waitUntil(() -> sheet() != null && sheet().getDialog() != null && sheet().getDialog().isShowing(), "sheet");
        PasswordFill stale = FillModules.lastFill;
        onMain(() -> {
            app.lockNow();
            return null;
        });
        instr().waitForIdleSync();
        assertStaleRefused(stale);
        waitUntil(() -> !(resumed() instanceof EntryEditActivity), "上鎖後離開編輯畫面");
    }

    @Test
    public void faultyActionKeepsEditing() {
        openEdit();
        clickText(app.getString(android.R.string.cut));
        assertTrue(failures.toString(), failures.contains(FillModules.Crashing.ID + ":perform"));
        assertTrue(onMain(() -> resumed() instanceof EntryEditActivity));
        assertEquals(OLD_PASSWORD, password());
        type(R.id.edit_password, "typed-by-hand-1");
        click(R.id.edit_save);
        waitUntil(() -> resumed() instanceof EntryDetailActivity, "儲存後的詳細");
    }

    private void openEdit() {
        // 從詳細開編輯（儲存後回到詳細，同實際的流程）
        app.startActivity(EntryDetailActivity.intent(app, entry)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof EntryDetailActivity, "條目詳細");
        click(R.id.detail_edit);
        waitUntil(() -> resumed() instanceof EntryEditActivity && OLD_PASSWORD.equals(shownPassword()), "編輯畫面");
        assertTrue(onMain(() -> SecureWindows.isSecure(resumed().getWindow())));
    }

    private static ViewGroup actions() {
        return resumed().findViewById(R.id.edit_password_actions);
    }

    /** @return 密碼欄的內容（測試執行緒） */
    private static String password() {
        return onMain(PasswordFillScreensTest::shownPassword);
    }

    /** @return 密碼欄的內容（主執行緒：{@code waitUntil}、{@code onMain} 裡面用） */
    private static String shownPassword() {
        return ((TextView) resumed().findViewById(R.id.edit_password)).getText().toString();
    }

    private DialogFragment sheet() {
        Activity a = resumed();
        if (!(a instanceof FragmentActivity fa)) {
            return null;
        }
        return (DialogFragment) fa.getSupportFragmentManager().findFragmentByTag(FillModules.SHEET_TAG);
    }

    /** 舊的回填：不填入、陣列一樣清零。 */
    private static void assertStaleRefused(PasswordFill stale) {
        assertNotNull(stale);
        char[] late = "late-value-9".toCharArray();
        assertFalse(onMain(stale::isActive));
        assertFalse(onMain(() -> stale.fill(late)));
        assertZeroed(late);
    }

    private static void assertZeroed(char[] value) {
        assertNotNull(value);
        for (char c : value) {
            assertEquals('\0', c);
        }
    }

    private void snap(String name) {
        if (out != null) {
            shot(out, name);
        }
    }
}
