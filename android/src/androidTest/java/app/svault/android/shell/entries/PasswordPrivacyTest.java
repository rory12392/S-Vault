package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 密碼欄不把明文交給無障礙服務（T-19；PROGRESS 未解問題「uiautomator dump 讀得到主密碼」）。系統的
 * {@code speak_password} 預設為 1 時，框架把密碼欄的明文放進無障礙節點與輸入事件；這裡以 {@link UiAutomation}
 * （和 uiautomator、TalkBack 等無障礙服務同一條路）讀解鎖畫面，並檢查建立保管庫、條目編輯的密碼欄。按「顯示」後畫面上
 * 是明文，無障礙也照常讀得到（TalkBack 使用者要能確認）。假資料（密碼 probe1234）。
 */
@RunWith(AndroidJUnit4.class)
public class PasswordPrivacyTest {

    private static final String SECRET = new String(TestVaults.password(), StandardCharsets.UTF_8);
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
        instr().getUiAutomation().setOnAccessibilityEventListener(null);
        files.close();
    }

    @Test
    public void unlockPasswordIsMaskedForAccessibility() {
        app.startActivity(UnlockActivity.intent(app, "content://app.svault.android.test.none/document/x",
                "privacy.kdbx").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof UnlockActivity, "解鎖畫面");
        // 檔案不存在時欄位可能停用；這裡只看欄位給無障礙的內容
        onMain(() -> {
            EditText field = resumed().findViewById(R.id.unlock_password);
            field.setEnabled(true);
            return field.requestFocus();
        });
        settle();
        UiAutomation ui = instr().getUiAutomation();
        List<String> leaked = new ArrayList<>();
        ui.setOnAccessibilityEventListener(e -> {
            if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    || e.getEventType() == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED) {
                for (CharSequence t : e.getText()) {
                    if (!onlyDots(t)) {
                        leaked.add("事件文字");
                    }
                }
                if (e.getBeforeText() != null && !onlyDots(e.getBeforeText())) {
                    leaked.add("事件的 beforeText");
                }
            }
        });

        instr().sendStringSync(SECRET);
        settle();

        AccessibilityNodeInfo node = findNode(ui, "app.svault.android:id/unlock_password");
        assertTrue(node.isPassword());
        assertEquals("節點只給遮罩字元（字數相同）", dots(SECRET.length()), String.valueOf(node.getText()));
        assertTrue("輸入事件沒有明文：" + leaked, leaked.isEmpty());

        onMain(() -> resumed().findViewById(R.id.unlock_password_layout)
                .findViewById(com.google.android.material.R.id.text_input_end_icon).performClick());
        settle();
        assertEquals("按「顯示」後照常讀得到", SECRET,
                String.valueOf(findNode(ui, "app.svault.android:id/unlock_password").getText()));
    }

    @Test
    public void createVaultPasswordsAreMaskedForAccessibility() {
        app.startActivity(new Intent(app, CreateVaultActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof CreateVaultActivity, "建立保管庫畫面");
        for (int id : new int[] {R.id.create_password, R.id.create_confirm}) {
            onMain(() -> {
                ((EditText) resumed().findViewById(id)).setText(SECRET);
                return null;
            });
            assertMasked(id);
        }
    }

    @Test
    public void entryPasswordIsMaskedForAccessibility() throws Exception {
        Vault v = new VaultCodec(TestVaults.fastParams()).create("遮罩測試", TestVaults.password());
        UUID id = new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "", ""));
        files.unlock(v, "遮罩測試.kdbx");
        app.startActivity(EntryEditActivity.edit(app, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof EntryEditActivity
                && ((EditText) resumed().findViewById(R.id.edit_password)).length() > 0, "編輯畫面");

        AccessibilityNodeInfo info = onMain(() -> resumed().findViewById(R.id.edit_password)
                .createAccessibilityNodeInfo());
        assertEquals("編輯畫面一開就填好的條目密碼也遮罩", dots("gh-Demo-2026!".length()), String.valueOf(info.getText()));
    }

    private static void assertMasked(int id) {
        AccessibilityNodeInfo info = onMain(() -> resumed().findViewById(id).createAccessibilityNodeInfo());
        assertTrue(info.isPassword());
        assertFalse("節點沒有明文", String.valueOf(info.getText()).contains(SECRET));
        assertEquals(dots(SECRET.length()), String.valueOf(info.getText()));
    }

    private static AccessibilityNodeInfo findNode(UiAutomation ui, String viewId) {
        for (int i = 0; i < 30; i++) {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) {
                List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByViewId(viewId);
                if (!found.isEmpty()) {
                    AccessibilityNodeInfo n = found.get(0);
                    n.refresh();
                    return n;
                }
            }
            android.os.SystemClock.sleep(200);
        }
        assertNotNull("找不到 " + viewId, null);
        return null;
    }

    private static boolean onlyDots(CharSequence t) {
        for (int i = 0; i < t.length(); i++) {
            if (t.charAt(i) != '•') {
                return false;
            }
        }
        return true;
    }

    private static String dots(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) {
            b.append('•');
        }
        return b.toString();
    }
}
