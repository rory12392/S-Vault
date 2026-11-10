package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Dialog;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.activity.ComponentDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.ui.DiffRow;
import app.svault.core.session.VaultSession;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * android 衝突對照（T-16；US-08）在真的 App 裡走一遍：PC 與另一台都改了 GitHub 的密碼、受保護的自訂欄位
 * 「PIN」、沒保護的「提示」，以及 Google 的帳號 → 首頁衝突 Banner →「處理」→ 對照畫面（第 n／N 筆、遮罩、未選完、
 * 稍後處理不寫遠端、再開時沿用選擇）→ 完成並儲存。假資料保管庫（密碼 probe1234）放在記憶體位置。
 *
 * <p>一般的 {@code connectedDebugAndroidTest} 只做檢查；帶 {@code -e shots <前綴>} 時另外截圖到 App 外部資料夾的
 * {@code shots/<前綴>/}（同 {@link EntryScreensTest}）。
 */
@RunWith(AndroidJUnit4.class)
public class ConflictScreensTest {

    private static final String MASK = "••••••••";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-20T06:30:00Z"), ZoneId.of("Asia/Taipei"));
    private SvaultApplication app;
    private TestVaultFiles files;
    private File out;

    @Before
    public void setUp() {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        String prefix = InstrumentationRegistry.getArguments().getString("shots");
        out = prefix == null ? null : new File(app.getExternalFilesDir(null), "shots/" + prefix);
    }

    @After
    public void tearDown() {
        files.close();
    }

    @Test
    public void conflictScreen() throws Exception {
        files.unlock(vault(), "衝突測試.kdbx");
        VaultSession session = app.vaultSession();
        UUID gh = find(session.vault(), "GitHub").uuid();
        UUID google = find(session.vault(), "Google").uuid();
        // 這台：離線時改了兩筆（存檔失敗，修改在 pending/）
        files.location().offline(true);
        VaultEditor mine = new VaultEditor(session.vault(), clock);
        mine.updateEntry(gh, new EntryDraft("GitHub", "rory-dev", "gh-這台-2027", "https://github.com/login", ""));
        TestVaults.setField(session.vault(), gh, "PIN", "1379", true);
        TestVaults.setField(session.vault(), gh, "提示", "這台改的提示", false);
        mine.updateEntry(google, new EntryDraft("Google", "this@gmail.test", "g00gle-demo", "", ""));
        try {
            session.save();
            fail("離線時存檔應該失敗");
        } catch (VaultException expected) {
            // 修改寫進 pending/
        }
        // 另一台：同樣的欄位改成別的值
        files.editRemote(v -> {
            VaultEditor other = new VaultEditor(v, clock);
            other.updateEntry(gh, new EntryDraft("GitHub", "rory-dev", "gh-另一台-2027", "https://github.com/login",
                    ""));
            TestVaults.setField(v, gh, "PIN", "2580", true);
            TestVaults.setField(v, gh, "提示", "另一台改的提示", false);
            other.updateEntry(google, new EntryDraft("Google", "other@gmail.test", "g00gle-demo", "", ""));
        });
        files.location().offline(false);
        files.relock();
        launchHome();
        waitUntil(() -> textShown(app.getString(R.string.entries_conflicts, 2)), "首頁出現衝突 Banner");
        shot("31-home-conflict");
        byte[] remoteBefore = files.location().content();

        // AC-08-1、AC-08-4：「處理」→ 第 1／2 筆，只列衝突的欄位
        clickText(app.getString(R.string.entries_resolve));
        waitUntil(() -> dialogRoot() != null, "對照畫面開啟");
        assertTrue("對照畫面的視窗要有 FLAG_SECURE", onMain(() -> (dialog().getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SECURE) != 0));
        assertEquals("解決衝突（第 1／2 筆）", onMain(() -> text(R.id.conflict_heading)));
        goTo("GitHub");
        assertEquals(3, onMain(() -> rows().getChildCount()).intValue());
        // 受保護的自訂欄位與密碼預設遮罩（T-15 的缺口）；沒保護的自訂欄位照常顯示
        for (String field : new String[] {"密碼", "自訂欄位「PIN」"}) {
            String shown = onMain(() -> option(row(field), R.id.sv_diff_this).getText().toString());
            assertTrue(field + "：" + shown, shown.contains(MASK));
            assertFalse(field, shown.contains("1379") || shown.contains("gh-這台"));
            assertTrue(onMain(() -> option(row(field), R.id.sv_diff_this).getContentDescription().toString())
                    .contains(app.getString(R.string.sv_field_masked)));
            assertEquals(View.VISIBLE, onMain(() -> row(field).findViewById(R.id.sv_diff_reveal).getVisibility())
                    .intValue());
        }
        assertTrue(onMain(() -> option(row("自訂欄位「提示」"), R.id.sv_diff_this).getText().toString())
                .contains("這台改的提示"));
        assertEquals(View.GONE, onMain(() -> row("自訂欄位「提示」").findViewById(R.id.sv_diff_reveal).getVisibility())
                .intValue());
        // TalkBack 可讀名稱：顯示鈕帶欄位名稱、關閉鈕是「稍後處理」
        assertEquals("顯示自訂欄位「PIN」", onMain(() -> row("自訂欄位「PIN」").findViewById(R.id.sv_diff_reveal)
                .getContentDescription().toString()));
        assertEquals(app.getString(R.string.conflict_later), onMain(() -> String.valueOf(
                ((com.google.android.material.appbar.MaterialToolbar) dialogRoot().findViewById(R.id.conflict_toolbar))
                        .getNavigationContentDescription())));
        shot("32-conflict-masked");

        // 沒選就按「下一筆」：提示「還有欄位沒有選擇」
        clickInDialog(R.id.conflict_next);
        assertEquals(View.VISIBLE, onMain(() -> dialogRoot().findViewById(R.id.conflict_missing).getVisibility())
                .intValue());
        shot("33-conflict-missing");

        // AC-08-2：「全部用這台裝置的」後，PIN 改選另一台；顯示 PIN
        clickInDialog(R.id.conflict_use_this);
        onMain(() -> option(row("自訂欄位「PIN」"), R.id.sv_diff_other).performClick());
        onMain(() -> row("自訂欄位「PIN」").findViewById(R.id.sv_diff_reveal).performClick());
        settle();
        assertEquals(View.GONE, onMain(() -> dialogRoot().findViewById(R.id.conflict_missing).getVisibility())
                .intValue());
        assertTrue(onMain(() -> option(row("自訂欄位「PIN」"), R.id.sv_diff_other).getText().toString())
                .contains("2580"));
        shot("34-conflict-chosen-revealed");

        // AC-08-3：稍後處理 → 不寫遠端、Banner 還在
        onMain(() -> {
            if (dialog() instanceof ComponentDialog d) {
                d.getOnBackPressedDispatcher().onBackPressed();
            } else {
                fragment().dismiss();
            }
            return null;
        });
        settle();
        assertNull("對照畫面已關閉", dialogRoot());
        assertArrayEquals("關閉不解決時不寫遠端", remoteBefore, files.location().content());
        assertTrue(textShown(app.getString(R.string.entries_conflicts, 2)));
        shot("35-after-later");

        // 再開：沿用剛才的選擇（D-105），從第一筆沒選完的開始（D-106）
        clickText(app.getString(R.string.entries_resolve));
        waitUntil(() -> dialogRoot() != null, "對照畫面再開");
        assertEquals("Google", onMain(() -> text(R.id.conflict_entry)));
        assertEquals(app.getString(R.string.conflict_finish),
                onMain(() -> ((TextView) dialogRoot().findViewById(R.id.conflict_next)).getText().toString()));
        clickInDialog(R.id.conflict_use_other);
        shot("36-conflict-last");
        clickInDialog(R.id.conflict_previous);
        assertEquals(DiffRow.Choice.OTHER_DEVICE, onMain(() -> row("自訂欄位「PIN」").getChoice()));
        assertEquals(DiffRow.Choice.THIS_DEVICE, onMain(() -> row("密碼").getChoice()));
        clickInDialog(R.id.conflict_next);
        clickInDialog(R.id.conflict_next);
        waitUntil(() -> dialogRoot() == null && !textShown(app.getString(R.string.entries_conflicts, 2)),
                "完成並儲存後 Banner 消失");
        shot("37-resolved");

        // 寫回的內容：選的值；沒被選的在歷史紀錄
        try (Vault remote = new VaultCodec(TestVaults.fastParams()).open(files.location().content(),
                TestVaults.password())) {
            EntryView g = find(remote, "GitHub");
            assertEquals("gh-這台-2027", g.password());
            assertEquals("2580", g.customFields().get("PIN"));
            assertEquals("這台改的提示", g.customFields().get("提示"));
            assertTrue(g.historyCount() > 0);
            assertEquals("other@gmail.test", find(remote, "Google").username());
        }
    }

    // ---------- 工具 ----------

    private void launchHome() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();
    }

    private void shot(String name) {
        if (out != null) {
            ScreenShots.shot(out, name);
        }
    }

    private void goTo(String title) {
        for (int i = 0; i < 2 && !title.equals(onMain(() -> text(R.id.conflict_entry))); i++) {
            clickInDialog(R.id.conflict_use_this);
            clickInDialog(R.id.conflict_next);
        }
        assertEquals(title, onMain(() -> text(R.id.conflict_entry)));
    }

    private static void clickInDialog(int id) {
        onMain(() -> dialogRoot().findViewById(id).performClick());
        settle();
    }

    private static DialogFragment fragment() {
        return (DialogFragment) ((FragmentActivity) resumed()).getSupportFragmentManager()
                .findFragmentByTag(ConflictDialog.TAG);
    }

    private static Dialog dialog() {
        return fragment().requireDialog();
    }

    /** @return 對照畫面的根部；沒開時 {@code null}（在主執行緒呼叫） */
    private static View dialogRoot() {
        DialogFragment f = fragment();
        return f == null || f.getDialog() == null || !f.getDialog().isShowing() ? null
                : f.getDialog().findViewById(R.id.conflict_root);
    }

    private static String text(int id) {
        return ((TextView) dialogRoot().findViewById(id)).getText().toString();
    }

    private static ViewGroup rows() {
        return dialogRoot().findViewById(R.id.conflict_rows);
    }

    private static DiffRow row(String field) {
        ViewGroup rows = rows();
        for (int i = 0; i < rows.getChildCount(); i++) {
            DiffRow r = (DiffRow) rows.getChildAt(i);
            if (field.contentEquals(((TextView) r.findViewById(R.id.sv_diff_field)).getText())) {
                return r;
            }
        }
        throw new AssertionError("找不到欄位：" + field);
    }

    private static TextView option(DiffRow row, int id) {
        return row.findViewById(id);
    }

    private boolean textShown(String text) {
        Boolean found = onMain(() -> {
            View banner = resumed().findViewById(R.id.page_banner);
            return banner != null && banner.getVisibility() == View.VISIBLE
                    && text.contentEquals(((TextView) banner.findViewById(R.id.sv_banner_text)).getText());
        });
        return Boolean.TRUE.equals(found);
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
            if (Boolean.TRUE.equals(onMain(done))) {
                settle();
                return;
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("等不到：" + what);
    }

    private static EntryView find(Vault v, String title) {
        return v.entries().stream().filter(e -> e.title().equals(title)).findFirst().orElseThrow();
    }

    /** 兩筆條目；GitHub 有受保護的自訂欄位「PIN」與沒保護的「提示」。 */
    private Vault vault() throws Exception {
        Vault v = new VaultCodec(TestVaults.fastParams()).create("衝突測試", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, clock);
        UUID root = v.groups().get(0).uuid();
        UUID gh = ed.addEntry(root, new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com/login",
                ""));
        TestVaults.setField(v, gh, "PIN", "0000", true);
        TestVaults.setField(v, gh, "提示", "生日", false);
        ed.addEntry(root, new EntryDraft("Google", "rory.demo@gmail.test", "g00gle-demo", "", ""));
        return v;
    }
}
