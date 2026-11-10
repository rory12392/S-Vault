package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Android 自動上鎖（T-18；AC-12-2、AC-12-3、AC-08-5）在真的 App 裡走一遍：切到背景（按 Home，回到桌面）後依設定的秒數
 * 上鎖、編輯畫面上沒儲存的內容上鎖前先存、衝突沒解決時上鎖後再解鎖回到對照畫面。螢幕關閉要按電源鍵，在
 * {@link ScreenOffLockTest}（要另外指定才跑）。假資料保管庫（密碼 probe1234）放在記憶體位置。
 */
@RunWith(AndroidJUnit4.class)
public class AutoLockScreensTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-21T06:30:00Z"), ZoneId.of("Asia/Taipei"));
    private SvaultApplication app;
    private TestVaultFiles files;
    private Settings before;

    @Before
    public void setUp() {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        before = app.settingsChanges().live().current();
        // 前一個測試結束時上鎖會導到解鎖或歡迎畫面（非同步）：等畫面穩定再開始
        settle();
        settle();
    }

    @After
    public void tearDown() {
        onMain(() -> app.settingsChanges().change(s -> before, () -> { }));
        files.close();
    }

    @Test
    public void ac12_2_backgroundLocksImmediatelyWhenSetToZero() throws Exception {
        backgroundSeconds(0);
        files.unlock(vault(), "背景上鎖.kdbx");
        launchHome();

        goToLauncher();

        waitUntil(() -> !app.vaultSession().isUnlocked(), "切到背景立即上鎖");
        assertTrue(app.lockGate().isLocked());
    }

    @Test
    public void ac12_2_backgroundLocksAfterConfiguredSeconds() throws Exception {
        backgroundSeconds(3);
        files.unlock(vault(), "背景上鎖.kdbx");
        launchHome();

        goToLauncher();
        SystemClock.sleep(1500);
        assertTrue("還沒到 3 秒", app.vaultSession().isUnlocked());

        waitUntil(() -> !app.vaultSession().isUnlocked(), "切到背景 3 秒後上鎖");
    }

    @Test
    public void ac12_2_returningInTimeKeepsUnlocked() throws Exception {
        backgroundSeconds(30);
        files.unlock(vault(), "背景上鎖.kdbx");
        launchHome();

        goToLauncher();
        SystemClock.sleep(1000);
        launchHome();

        assertTrue(app.vaultSession().isUnlocked());
        assertTrue(onMain(() -> resumed() instanceof HomeActivity));
    }

    @Test
    public void ac12_3_unsavedEditIsSavedWhenLocked() throws Exception {
        backgroundSeconds(0);
        files.unlock(vault(), "編輯中上鎖.kdbx");
        UUID id = find(app.vaultSession().vault(), "GitHub").uuid();
        app.startActivity(EntryEditActivity.edit(app, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof EntryEditActivity
                && "GitHub".contentEquals(((EditText) resumed().findViewById(R.id.edit_name)).getText()), "編輯畫面");
        onMain(() -> {
            ((EditText) resumed().findViewById(R.id.edit_notes)).setText("上鎖前還沒按儲存");
            return null;
        });

        goToLauncher();

        waitUntil(() -> !app.vaultSession().isUnlocked(), "切到背景上鎖");
        try (Vault v = new VaultCodec(TestVaults.fastParams()).open(files.location().content(),
                TestVaults.password())) {
            assertEquals("上鎖前還沒按儲存", find(v, "GitHub").notes());
        }
    }

    @Test
    public void ac08_5_conflictResumesAfterLock() throws Exception {
        backgroundSeconds(0);
        files.unlock(vault(), "衝突上鎖.kdbx");
        VaultSession session = app.vaultSession();
        UUID vaultId = session.vault().id();
        UUID gh = find(session.vault(), "GitHub").uuid();
        // 這台離線改密碼（進 pending/）、另一台改成別的值 → 解鎖後衝突
        files.location().offline(true);
        new VaultEditor(session.vault(), clock).updateEntry(gh, new EntryDraft("GitHub", "rory-dev", "gh-這台-2027",
                "", ""));
        try {
            session.save();
            fail("離線時存檔應該失敗");
        } catch (VaultException expected) {
            // 修改寫進 pending/
        }
        files.editRemote(v -> new VaultEditor(v, clock).updateEntry(gh, new EntryDraft("GitHub", "rory-dev",
                "gh-另一台-2027", "", "")));
        files.location().offline(false);
        files.relock();
        launchHome();
        waitUntil(() -> bannerShows(app.getString(R.string.entries_conflicts, 1)), "首頁出現衝突 Banner");
        clickText(app.getString(R.string.entries_resolve));
        waitUntil(() -> dialogRoot() != null, "對照畫面開啟");
        onMain(() -> dialogRoot().findViewById(R.id.conflict_use_this).performClick());
        settle();
        byte[] remoteBefore = files.location().content();

        goToLauncher();

        waitUntil(() -> !app.vaultSession().isUnlocked(), "切到背景上鎖");
        assertArrayEquals("衝突沒解決：不寫遠端（AC-08-3）", remoteBefore, files.location().content());
        Path draft = app.localStore().conflictFile(vaultId, files.location().persistentId());
        assertTrue("選擇加密保存", Files.exists(draft));
        String raw = new String(Files.readAllBytes(draft), StandardCharsets.ISO_8859_1);
        for (String plain : new String[] {"GitHub", "rory-dev", "gh-", "probe1234", "Password"}) {
            assertFalse("暫存檔有明文：" + plain, raw.contains(plain));
        }

        files.relock();
        launchHome();
        waitUntil(() -> dialogRoot() != null, "解鎖後回到對照畫面");
        assertTrue("帶回上鎖前的選擇", onMain(() -> ((CompoundButton) firstRow().findViewById(R.id.sv_diff_this))
                .isChecked()));
    }

    // ---------- 工具 ----------

    private void backgroundSeconds(int seconds) {
        onMain(() -> app.settingsChanges().change(s -> Settings.AUTO_LOCK_ANDROID_SECONDS.write(s, seconds),
                () -> { }));
    }

    private void launchHome() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();
    }

    /** 按 Home：回到桌面，App 的畫面全部停止（切到背景）。 */
    private static void goToLauncher() {
        instr().getContext().startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instr().waitForIdleSync();
    }

    private boolean bannerShows(String text) {
        View banner = resumed().findViewById(R.id.page_banner);
        return banner != null && banner.getVisibility() == View.VISIBLE
                && text.contentEquals(((TextView) banner.findViewById(R.id.sv_banner_text)).getText());
    }

    /** @return 對照畫面的根部；沒開時 {@code null}（在主執行緒呼叫） */
    private static View dialogRoot() {
        DialogFragment f = (DialogFragment) ((FragmentActivity) resumed()).getSupportFragmentManager()
                .findFragmentByTag(ConflictDialog.TAG);
        return f == null || f.getDialog() == null || !f.getDialog().isShowing() ? null
                : f.getDialog().findViewById(R.id.conflict_root);
    }

    private static View firstRow() {
        return ((ViewGroup) dialogRoot().findViewById(R.id.conflict_rows)).getChildAt(0);
    }

    private static <T> T onMain(Supplier<T> get) {
        Object[] box = new Object[1];
        instr().runOnMainSync(() -> box[0] = get.get());
        @SuppressWarnings("unchecked")
        T t = (T) box[0];
        return t;
    }

    private static void waitUntil(Supplier<Boolean> done, String what) {
        for (int i = 0; i < 100; i++) {
            Boolean ok;
            try {
                ok = onMain(done);
            } catch (AssertionError | RuntimeException e) {
                // 換畫面途中沒有前景的 Activity
                ok = false;
            }
            if (Boolean.TRUE.equals(ok)) {
                settle();
                return;
            }
            SystemClock.sleep(100);
        }
        String front;
        try {
            front = onMain(() -> resumed().getClass().getSimpleName());
        } catch (AssertionError | RuntimeException e) {
            front = "沒有";
        }
        throw new AssertionError("等不到：" + what + "（前景：" + front + "）");
    }

    private static EntryView find(Vault v, String title) {
        return v.entries().stream().filter(e -> e.title().equals(title)).findFirst().orElseThrow();
    }

    private Vault vault() throws Exception {
        Vault v = new VaultCodec(TestVaults.fastParams()).create("上鎖測試", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, clock);
        UUID root = v.groups().get(0).uuid();
        ed.addEntry(root, new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com/login", ""));
        return v;
    }
}
