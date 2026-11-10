package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.back;
import static app.svault.android.shell.entries.ScreenShots.click;
import static app.svault.android.shell.entries.ScreenShots.clickText;
import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.longClickText;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;
import static app.svault.android.shell.entries.ScreenShots.shot;
import static app.svault.android.shell.entries.ScreenShots.type;

import android.content.Intent;
import android.os.Bundle;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import com.google.android.material.search.SearchView;
import java.io.File;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 條目畫面的驗收截圖（DESIGN §7：淺色／深色 × 預設與最大字體；STACK §7 第 7 點）。實際的畫面與流程：解鎖測試保管庫
 * （假資料，密碼 probe1234）→ 群組清單 → 條目清單 → 搜尋 → 詳細 → 編輯，含載入中、存檔中、存檔失敗、確認刪除、
 * 捨棄修改、空狀態。只在 {@code -e shots <前綴>} 時執行（一般的 connectedDebugAndroidTest 略過），
 * 圖存到 App 外部資料夾的 {@code shots/<前綴>/}，用 {@code adb pull} 取回。
 */
@RunWith(AndroidJUnit4.class)
public class EntryScreensTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T06:30:00Z"), ZoneId.of("Asia/Taipei"));
    private SvaultApplication app;
    private TestVaultFiles files;
    private File out;

    @Before
    public void setUp() {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            // 一般的 connectedDebugAndroidTest 不截圖（AGP 會把 Assume 的略過算成失敗）
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
        // 載入中：卡住背景執行緒，首頁顯示「正在載入條目…」
        files.unlock(demo(), "我的密碼.kdbx");
        CountDownLatch hold = files.holdWorker();
        launchHome();
        shot(out, "02-home-loading");
        hold.countDown();
        shot(out, "01-home");

        clickText("工作");
        shot(out, "03-list-group");
        back();
        clickText("旅遊");
        shot(out, "05-list-empty-group");
        back();
        clickText("資源回收桶");
        shot(out, "06-list-bin");
        clickText("舊的路由器");
        shot(out, "13-detail-in-bin");
        back();
        back();

        click(R.id.page_search_bar);
        searchFor("git");
        shot(out, "07-search");
        searchFor("zzz");
        shot(out, "08-search-none");
        instr().runOnMainSync(() -> ((SearchView) resumed().findViewById(R.id.page_search_view)).hide());
        settle();

        clickText("全部條目");
        shot(out, "04-list-all");
        clickText("GitHub");
        shot(out, "09-detail");
        instr().runOnMainSync(() -> resumed().findViewById(R.id.detail_password)
                .findViewById(R.id.sv_field_reveal).performClick());
        shot(out, "10-detail-revealed");
        instr().runOnMainSync(() -> resumed().findViewById(R.id.detail_password)
                .findViewById(R.id.sv_field_copy).performClick());
        shot(out, "11-detail-copied");
        click(R.id.detail_delete);
        shot(out, "12-confirm-delete");
        clickText(app.getString(R.string.sv_dialog_cancel));

        click(R.id.detail_edit);
        shot(out, "14-edit");
        type(R.id.edit_password, "gh-Demo-2027?");
        back();
        shot(out, "17-discard");
        clickText(app.getString(R.string.entries_discard_keep));
        hold = files.holdWorker();
        click(R.id.edit_save);
        shot(out, "16-edit-saving");
        hold.countDown();
        shot(out, "18-detail-saved");
        back();

        click(R.id.page_add);
        click(R.id.edit_save);
        shot(out, "15-edit-name-error");
        back();
        back();

        longClickText("工作");
        shot(out, "19-group-menu");
        clickText(app.getString(R.string.entries_group_delete));
        shot(out, "20-confirm-delete-group");
        clickText(app.getString(R.string.sv_dialog_cancel));
        longClickText("工作");
        clickText(app.getString(R.string.entries_group_add));
        clickText(app.getString(R.string.entries_name_add));
        shot(out, "21-name-error");
        clickText(app.getString(R.string.sv_dialog_cancel));

        // 存檔失敗：遠端讀不到 → 修改留在記憶體、「尚未同步」Banner；恢復後「再試一次」
        files.location().offline(true);
        clickText("全部條目");
        clickText("Google");
        click(R.id.detail_edit);
        type(R.id.edit_username, "rory.new@gmail.test");
        click(R.id.edit_save);
        shot(out, "22-detail-unsaved");
        files.location().offline(false);
        clickText(app.getString(R.string.entries_retry));
        shot(out, "23-detail-retried");
        back();
        back();

        // T-14：另一台裝置改過 → 重新解鎖時「正在同步最新資料…」→ 合併後 Snackbar「已更新為最新資料（合併 2 筆）」
        byte[] beforeMerge = files.location().content();
        files.editRemote(v -> {
            VaultEditor other = new VaultEditor(v, clock);
            other.addEntry(v.id(), new EntryDraft("另一台新增", "rory", "other-1", "", ""));
            EntryView netflix = find(v, "Netflix");
            other.updateEntry(netflix.uuid(), new EntryDraft("Netflix", netflix.username(), "n3tflix-2027",
                    netflix.url(), netflix.notes()));
        });
        files.relock();
        hold = files.holdWorker();
        launchHome();
        shot(out, "27-home-syncing");
        hold.countDown();
        shot(out, "28-home-merged");

        // T-27（AC-07-9）：另一台以不含這台修改的舊版本整份取代雲端（Drive 恢復同步）→ 兩邊都保留，Snackbar 多一行通知
        files.location().replace(beforeMerge);
        files.editRemote(v -> new VaultEditor(v, clock).addEntry(v.id(),
                new EntryDraft("PC 新增", "pc", "pc-1", "", "")));
        files.relock();
        launchHome();
        shot(out, "38-home-replaced");

        // 有衝突（AC-08-1、AC-08-3）：離線時改了 GitHub 的密碼、另一台也改了 → 恢復連線後「再試一次」→ 衝突 Banner
        files.location().offline(true);
        clickText("全部條目");
        clickText("GitHub");
        click(R.id.detail_edit);
        type(R.id.edit_password, "gh-這台-2027");
        click(R.id.edit_save);
        files.editRemote(v -> {
            EntryView gh = find(v, "GitHub");
            new VaultEditor(v, clock).updateEntry(gh.uuid(), new EntryDraft("GitHub", gh.username(), "gh-另一台-2027",
                    gh.url(), gh.notes()));
        });
        files.location().offline(false);
        clickText(app.getString(R.string.entries_retry));
        shot(out, "29-detail-conflict");
        back();
        shot(out, "30-list-conflict");
        back();

        // 空的保管庫
        files.unlock(codec().create("空的保管庫", TestVaults.password()), "空的保管庫.kdbx");
        launchHome();
        shot(out, "24-home-empty");
        clickText("全部條目");
        shot(out, "25-list-vault-empty");
        back();
        clickText("資源回收桶");
        shot(out, "26-list-bin-empty");
    }

    private void launchHome() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        settle();
    }

    private static void searchFor(String text) {
        instr().runOnMainSync(() -> ((SearchView) resumed().findViewById(R.id.page_search_view)).setText(text));
        settle();
    }

    private static EntryView find(Vault v, String title) {
        return v.entries().stream().filter(e -> e.title().equals(title)).findFirst().orElseThrow();
    }

    private static VaultCodec codec() {
        return new VaultCodec(TestVaults.fastParams());
    }

    /** 與 T-10 的 demo.kdbx 相同的假資料：三個群組（含子群組）、幾筆條目、回收桶裡一筆。 */
    private Vault demo() throws Exception {
        Vault v = codec().create("我的密碼", TestVaults.password());
        VaultEditor ed = new VaultEditor(v, clock);
        UUID root = v.groups().get(0).uuid();
        UUID work = ed.addGroup(root, "工作");
        UUID home = ed.addGroup(root, "家庭");
        UUID bank = ed.addGroup(home, "銀行");
        ed.addGroup(root, "旅遊");
        ed.addEntry(root, new EntryDraft("GitHub", "rory-dev", "gh-Demo-2026!", "https://github.com/login",
                "雙重驗證用手機 App"));
        ed.addEntry(root, new EntryDraft("Google", "rory.demo@gmail.test", "g00gle-demo", "accounts.google.com", ""));
        ed.addEntry(root, new EntryDraft("Netflix", "family@example.test", "n3tflix", "https://www.netflix.com", ""));
        ed.addEntry(work, new EntryDraft("公司 VPN", "rory", "vpn-demo-123", "https://vpn.example.test",
                "只能在公司網路外使用"));
        ed.addEntry(work, new EntryDraft("Jira", "rory@example.test", "jira-demo", "https://example.atlassian.net", ""));
        ed.addEntry(bank, new EntryDraft("網路銀行", "A123456789", "bank-demo-pw", "https://ibank.example.test",
                "密碼每 90 天要換"));
        UUID old = ed.addEntry(home, new EntryDraft("舊的路由器", "admin", "admin", "http://192.168.1.1", ""));
        ed.recycleEntry(old);
        return v;
    }
}
