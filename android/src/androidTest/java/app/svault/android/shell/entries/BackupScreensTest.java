package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.ScreenShots.shot;

import android.content.Intent;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.start.BackupActivity;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.File;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 備份還原（解鎖中，T-12）的驗收截圖：首頁溢位選單 → 備份清單 → 確認 → 還原中 → 首頁「已還原」，以及沒有備份。
 * 首頁有 {@code FLAG_SECURE}，所以和 {@link EntryScreensTest} 一樣在 App 行程內截圖；只在 {@code -e shots <前綴>}
 * 時執行。檔案損壞（上鎖中）的流程在解鎖畫面（沒有 {@code FLAG_SECURE}），以實機選檔走一次並用 screencap 截圖。
 */
@RunWith(AndroidJUnit4.class)
public class BackupScreensTest {

    private SvaultApplication app;
    private TestVaultFiles files;
    private File out;

    @Before
    public void setUp() {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            return;
        }
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        files = new TestVaultFiles(app);
        out = new File(app.getExternalFilesDir(null), "shots/" + prefix);
    }

    @After
    public void tearDown() {
        if (files != null) {
            files.close();
        }
    }

    @Test
    public void screens() throws Exception {
        if (files == null) {
            return;
        }
        // 沒有備份：剛建立、還沒存過檔
        files.unlock(codec().create("空的保管庫", TestVaults.password()), "空的保管庫.kdbx");
        launchHome();
        openBackups();
        shot(out, "31-backup-empty");

        // 存三次 → 三份備份（備份檔名用手機的當地時間）
        files.unlock(codec().create("我的密碼", TestVaults.password()), "我的密碼.kdbx");
        for (String title : new String[] {"GitHub", "Google", "Netflix"}) {
            Vault v = app.vaultSession().vault();
            new VaultEditor(v, Clock.systemDefaultZone()).addEntry(v.id(),
                    new EntryDraft(title, "rory", "pw-" + title, "", ""));
            app.vaultSession().save();
            Thread.sleep(1100);
        }
        launchHome();
        shot(out, "30-home-before");
        openBackups();
        shot(out, "32-backup-list");
        clickText(firstRowText());
        shot(out, "33-backup-selected");
        clickText(app.getString(R.string.restore_submit));
        shot(out, "34-backup-confirm");
        CountDownLatch hold = files.holdWorker();
        clickText(app.getString(R.string.restore_confirm_action));
        shot(out, "35-backup-restoring");
        hold.countDown();
        settle();
        shot(out, "36-home-restored");
    }

    private void launchHome() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();
    }

    /** 和溢位選單的「備份還原」相同的 Intent（選單本身是 PopupWindow，截圖畫不出底色）。 */
    private void openBackups() {
        app.startActivity(BackupActivity.unlocked(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        settle();
    }

    /** @return 清單第一列（最新的一份）的文字 */
    private static String firstRowText() {
        String[] text = new String[1];
        instr().runOnMainSync(() -> {
            androidx.recyclerview.widget.RecyclerView list = ScreenShots.resumed().findViewById(R.id.backup_list);
            text[0] = ((android.widget.TextView) list.getChildAt(0)).getText().toString();
        });
        return text[0];
    }

    private static VaultCodec codec() {
        return new VaultCodec(TestVaults.fastParams());
    }
}
