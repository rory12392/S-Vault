package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.start.CreateVaultActivity;
import app.svault.android.shell.start.UnlockActivity;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.time.Clock;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * T-19 交給 T-20 的兩項：主密碼欄不交給其他自動填入服務（不會被提議「儲存密碼」）；解鎖與建立
 * 保管庫畫面平常沒有 {@code FLAG_SECURE}（D-58，可以截圖驗收），但按「顯示密碼」看到明文的期間要有，切回遮罩後恢復。
 * 本來就有 {@code FLAG_SECURE} 的畫面（條目編輯）切回遮罩後仍然有。假資料（密碼 probe1234）。
 */
@RunWith(AndroidJUnit4.class)
public class RevealSecureTest {

    private SvaultApplication app;
    private TestVaultFiles files;

    @Before
    public void setUp() {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        settle();
    }

    @After
    public void tearDown() {
        files.close();
    }

    @Test
    public void unlockPasswordIsNotOfferedToAutofillServices() {
        openUnlock();
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO,
                (int) onMain(() -> resumed().findViewById(R.id.unlock_password).getImportantForAutofill()));
    }

    @Test
    public void createPasswordsAreNotOfferedToAutofillServices() {
        openCreate();
        for (int id : new int[] {R.id.create_password, R.id.create_confirm}) {
            assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO,
                    (int) onMain(() -> resumed().findViewById(id).getImportantForAutofill()));
        }
    }

    @Test
    public void unlockScreenIsSecureOnlyWhilePasswordIsShown() {
        openUnlock();
        onMain(() -> {
            ((EditText) resumed().findViewById(R.id.unlock_password)).setText("probe1234");
            return null;
        });
        assertFalse("遮罩時照 D-58 沒有 FLAG_SECURE", secure());

        toggle(R.id.unlock_password_layout);
        assertTrue("顯示主密碼時要有 FLAG_SECURE", secure());

        toggle(R.id.unlock_password_layout);
        assertFalse("切回遮罩後恢復", secure());
    }

    @Test
    public void createScreenIsSecureWhileEitherPasswordIsShown() {
        openCreate();
        toggle(R.id.create_password_layout);
        assertTrue(secure());
        toggle(R.id.create_confirm_layout);
        toggle(R.id.create_password_layout);
        assertTrue("確認欄仍顯示明文", secure());
        toggle(R.id.create_confirm_layout);
        assertFalse(secure());
    }

    @Test
    public void secureScreenStaysSecureAfterHiding() throws Exception {
        Vault v = new VaultCodec(TestVaults.fastParams()).create("顯示測試", TestVaults.password());
        UUID id = new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "", ""));
        files.unlock(v, "顯示測試.kdbx");
        app.startActivity(EntryEditActivity.edit(app, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof EntryEditActivity, "編輯畫面");
        settle();
        assertTrue(secure());

        toggle(R.id.edit_password_layout);
        toggle(R.id.edit_password_layout);
        assertTrue("條目畫面本來就有 FLAG_SECURE，切回遮罩不能拿掉", secure());
    }

    private void openUnlock() {
        app.startActivity(UnlockActivity.intent(app, "content://app.svault.android.test.none/document/x",
                "reveal.kdbx").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof UnlockActivity, "解鎖畫面");
        settle();
    }

    private void openCreate() {
        app.startActivity(new Intent(app, CreateVaultActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof CreateVaultActivity, "建立保管庫畫面");
        settle();
    }

    private static void toggle(int layoutId) {
        onMain(() -> resumed().findViewById(layoutId)
                .findViewById(com.google.android.material.R.id.text_input_end_icon).performClick());
        settle();
    }

    private static boolean secure() {
        return onMain(() -> (resumed().getWindow().getAttributes().flags
                & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }
}
