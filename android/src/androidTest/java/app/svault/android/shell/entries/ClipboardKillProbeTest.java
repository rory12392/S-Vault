package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.TestWaits.onMain;
import static app.svault.android.shell.entries.TestWaits.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.platform.ClipboardAlarm;
import app.svault.android.shell.start.WelcomeActivity;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.time.Clock;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * App 行程被結束後剪貼簿仍會清除（T-28）的實機探針，分兩段由 {@code .localdata\tmp\t-28\kill-probe.ps1} 叫：
 * <ol>
 *   <li>{@code -e killprobe arm}（配 {@code am instrument --no-restart}，測試結束後行程與畫面留著）：設定 10 秒，
 *       從詳細頁按「複製」密碼；</li>
 *   <li>腳本從最近使用的 App 把 S-Vault 滑掉、確認行程已結束、等鬧鐘；</li>
 *   <li>{@code -e killprobe check}：在前景讀剪貼簿，應已清空；還原設定。</li>
 * </ol>
 * 一般的 connectedDebugAndroidTest 不跑（同 ScreenOffLockTest）。假資料。
 */
@RunWith(AndroidJUnit4.class)
public class ClipboardKillProbeTest {

    private static final String SECRET = "gh-Demo-2026!";

    @Test
    public void arm() throws Exception {
        if (!"arm".equals(InstrumentationRegistry.getArguments().getString("killprobe"))) {
            return;
        }
        SvaultApplication app = app();
        settle();
        onMain(() -> app.settingsChanges().change(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 10), () -> { }));
        Vault v = new VaultCodec(TestVaults.fastParams()).create("滑掉測試", TestVaults.password());
        UUID entry = new VaultEditor(v, Clock.systemUTC()).addEntry(v.groups().get(0).uuid(),
                new EntryDraft("GitHub", "rory-dev", SECRET, "", ""));
        new TestVaultFiles(app).unlock(v, "滑掉測試.kdbx");
        // --no-restart 時測試類別和 App 不在同一個類別載入器，碰不到套件私有的 EntryDetailActivity.intent，自己組
        app.startActivity(new Intent().setClassName(app, "app.svault.android.shell.entries.EntryDetailActivity")
                .putExtra("app.svault.android.extra.ENTRY", entry.toString())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> "EntryDetailActivity".equals(resumed().getClass().getSimpleName()), "詳細頁");
        onMain(() -> resumed().findViewById(R.id.detail_password).findViewById(R.id.sv_field_copy).performClick());
        settle();
        assertEquals(SECRET, clipText(app));
        assertTrue("備援鬧鐘已排定", ClipboardAlarm.isScheduled(app));
    }

    @Test
    public void check() {
        if (!"check".equals(InstrumentationRegistry.getArguments().getString("killprobe"))) {
            return;
        }
        SvaultApplication app = app();
        app.startActivity(new Intent(app, WelcomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        // 有最近開啟的保管庫時歡迎畫面會轉到解鎖畫面；只要 S-Vault 在前景就讀得到剪貼簿
        waitUntil(() -> resumed() != null, "S-Vault 的畫面");
        String left = clipText(app);
        onMain(() -> app.settingsChanges().change(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 30), () -> { }));
        assertEquals("行程被結束後剪貼簿應已清除", null, left);
    }

    private static SvaultApplication app() {
        return (SvaultApplication) instr().getTargetContext().getApplicationContext();
    }

    /** @return 剪貼簿的文字；清除後為 {@code null} */
    private static String clipText(SvaultApplication app) {
        return onMain(() -> {
            ClipData c = app.getSystemService(ClipboardManager.class).getPrimaryClip();
            if (c == null || c.getItemCount() == 0 || c.getItemAt(0).getText() == null
                    || c.getItemAt(0).getText().length() == 0) {
                return null;
            }
            return c.getItemAt(0).getText().toString();
        });
    }
}
