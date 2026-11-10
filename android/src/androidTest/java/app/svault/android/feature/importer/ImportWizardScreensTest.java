package app.svault.android.feature.importer;

import static app.svault.android.feature.importer.ImpTestSupport.descendants;
import static app.svault.android.feature.importer.ImpTestSupport.onMain;
import static app.svault.android.feature.importer.ImpTestSupport.resumed;
import static app.svault.android.feature.importer.ImpTestSupport.shot;
import static app.svault.android.feature.importer.ImpTestSupport.shotNow;
import static app.svault.android.feature.importer.ImpTestSupport.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.entries.HomeActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.feature.importer.ImportWizardModel.Busy;
import app.svault.core.feature.importer.ImportWizardModel.Step;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.KdfParams;
import app.svault.core.vault.TestVaults;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 匯入精靈（T-62）：從首頁溢位選單開（真的模組，經 ServiceLoader 註冊）、選檔後預覽（筆數、前幾筆、密碼遮罩、
 * 跳過的行號、可能重複與整批的選擇）、匯入到「Chrome 匯入」群組、完成頁提示刪除 CSV、重建時沿用狀態、檔案層級的錯誤。
 * 只用記憶體裡的假資料保管庫與假資料 CSV（經測試 APK 的文件提供者）；系統檔案選擇器不由測試操作，直接把文件交給
 * {@link ImportWizardFragment#onPicked}（系統選擇器在實機上人手走一次，dev-log）。
 *
 * <p>帶 {@code -e shots <前綴>} 時另外截驗收圖（淺色／深色 × 預設與最大字體由 adb 切換後各跑一次）。帶 {@code -e bigcsv 1} 時
 * {@link #bigImport()} 用正式 KDF 參數的保管庫匯入 2,000 筆，截讀取與匯入中的 ProgressOverlay，耗時印在 logcat tag
 * {@code SVaultPerf}。
 */
@RunWith(AndroidJUnit4.class)
public class ImportWizardScreensTest {

    private static final String HEADER = "name,url,username,password,note\n";
    /** 三筆可匯入（第 2 行和既有條目可能重複）、第 5 行格式錯誤。 */
    private static final String SAMPLE = HEADER
            + "商店,https://shop.example.org/login,bob,Fake-Pass-1,\n"
            + "論壇,https://forum.example.net/,carol,Fake-Pass-2,\"備註, 含逗號\n與換行\"\n"
            + "壞的,只有兩欄\n"
            + ",https://www.example.com/,dave,Fake-Pass-3,\n";

    private SvaultApplication app;
    private ImpTestSupport vault;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) ImpTestSupport.instr().getTargetContext().getApplicationContext();
        vault = new ImpTestSupport(app);
        vault.unlockWith(List.of(new EntryDraft("舊的商店", "Bob", "probe1234", "https://www.shop.example.org/", "")),
                TestVaults.fastParams());
    }

    @After
    public void tearDown() {
        vault.close();
    }

    /** AC-14-1～AC-14-4：選檔 → 預覽（遮罩、跳過、可能重複）→ 匯入到新群組 → 完成頁提示刪除 CSV。 */
    @Test
    public void ac14_previewImportDone() throws Exception {
        Uri csv = vault.document("chrome-fake.csv", SAMPLE);
        openFromMenu();
        assertEquals(app.getString(R.string.imp_page_title), onMain(() -> String.valueOf(
                ((MaterialToolbar) resumed().findViewById(R.id.sv_host_toolbar)).getTitle())));
        assertEquals(View.VISIBLE, visibility(R.id.imp_choose_section));

        pick(csv);
        waitUntil(() -> model().step() == Step.PREVIEW, "預覽");
        assertEquals("共 3 筆", text(R.id.imp_total));
        assertEquals("chrome-fake.csv", text(R.id.imp_source));
        // 第 3 行那筆的備註有換行（佔第 3、4 行），格式錯誤的列在第 5 行
        List<String> skipped = onMain(() -> texts(R.id.imp_skipped));
        assertTrue(skipped.toString(), skipped.contains("跳過 1 列：第 5 行（格式錯誤，這些列不會匯入）"));
        assertEquals("有 1 筆的網址網域和帳號都和保管庫裡的條目相同，可能已經存在：第 2 行", text(R.id.imp_duplicates));
        assertEquals("略過可能重複的 1 筆", text(R.id.imp_dup_skip));
        assertTrue(onMain(() -> ((RadioGroup) resumed().findViewById(R.id.imp_dup_group)).getCheckedRadioButtonId()
                == R.id.imp_dup_skip));
        List<String> rows = onMain(() -> texts(R.id.imp_preview));
        assertTrue(rows.toString(), rows.containsAll(List.of("商店", "可能重複", "帳號：bob",
                "網址：https://shop.example.org/login", "密碼：" + ImportWizardFragment.MASK,
                "www.example.com")));
        assertFalse("預覽不顯示密碼", rows.toString().contains("Fake-Pass"));
        assertEquals("可能重複的標記只在第 1 筆", 1L, (long) onMain(() -> descendants(
                resumed().findViewById(R.id.imp_preview)).stream()
                .filter(v -> v.getId() == R.id.imp_row_tag && v.getVisibility() == View.VISIBLE).count()));
        assertEquals(app.getString(R.string.imp_preview_masked), onMain(() -> String.valueOf(
                resumed().findViewById(R.id.imp_preview).findViewById(R.id.imp_row_password)
                        .getContentDescription())));
        assertEquals("匯入到「Chrome 匯入」群組", text(R.id.imp_import));
        assertTrue(onMain(() -> resumed().findViewById(R.id.imp_import).isEnabled()));

        int writes = vault.writes();
        onMain(() -> resumed().findViewById(R.id.imp_import).performClick());
        waitUntil(() -> model().step() == Step.DONE, "完成", 20);
        assertEquals("整批存檔一次", writes + 1, vault.writes());
        assertEquals("已匯入 2 筆", text(R.id.imp_done));
        assertEquals("已放進新的「Chrome 匯入」群組。", text(R.id.imp_done_group));
        assertTrue(onMain(() -> texts(R.id.imp_delete_csv))
                .contains("CSV 檔中的密碼沒有加密，任何人打開都看得到。建議現在刪除這個檔案。"));
        assertEquals("chrome-fake.csv", text(R.id.imp_path));
        try (InputStream in = app.getContentResolver().openInputStream(csv)) {
            assertEquals("不替使用者刪 CSV", SAMPLE.getBytes(StandardCharsets.UTF_8).length, in.readAllBytes().length);
        }

        UnlockedEntries entries = ModuleHost.of(app).unlockedEntries().orElseThrow();
        List<EntryView> got = entries.all().stream()
                .filter(e -> entries.groupPath(e.groupId()).equals("匯入測試 / Chrome 匯入")).toList();
        assertEquals(2, got.size());
        EntryView forum = got.stream().filter(e -> e.title().equals("論壇")).findFirst().orElseThrow();
        assertEquals("Fake-Pass-2", forum.password());
        assertEquals("備註, 含逗號\n與換行", forum.notes());

        onMain(() -> resumed().findViewById(R.id.imp_again).performClick());
        waitUntil(() -> model().step() == Step.CHOOSE, "回到第 1 步");
        assertEquals(View.VISIBLE, visibility(R.id.imp_choose_section));
    }

    /** T-62：重建（旋轉、換主題、改字體）時沿用 Activity 範圍的狀態：預覽與「照樣匯入」的選擇還在。 */
    @Test
    public void recreateKeepsPreview() throws Exception {
        Uri csv = vault.document("chrome-fake.csv", SAMPLE);
        openFromMenu();
        pick(csv);
        waitUntil(() -> model().step() == Step.PREVIEW, "預覽");
        onMain(() -> resumed().findViewById(R.id.imp_dup_keep).performClick());
        assertEquals(3, (int) onMain(() -> model().toImport()));
        ImportWizardModel before = onMain(ImportWizardScreensTest::model);

        Activity old = onMain(ImpTestSupport::resumed);
        onMain(() -> {
            old.recreate();
            return null;
        });
        waitUntil(() -> resumed() != old && resumed().findViewById(R.id.imp_page) != null
                && resumed().findViewById(R.id.imp_preview_section).getVisibility() == View.VISIBLE, "重建後的預覽");
        assertTrue(onMain(() -> model() == before));
        assertTrue(onMain(() -> ((RadioGroup) resumed().findViewById(R.id.imp_dup_group)).getCheckedRadioButtonId()
                == R.id.imp_dup_keep));
        assertEquals("共 3 筆", text(R.id.imp_total));
    }

    /** 整個檔案不能匯入、讀不到：留在第 1 步，error Banner 說明。 */
    @Test
    public void fileProblemsStayOnFirstStep() throws Exception {
        Uri notChrome = vault.document("other.csv", "title,login\nx,y\n");
        openFromMenu();
        pick(notChrome);
        waitUntil(() -> model().busy() == Busy.NONE && model().problem().isPresent(), "缺欄位");
        assertEquals(View.VISIBLE, visibility(R.id.imp_problem));
        assertTrue(onMain(() -> texts(R.id.imp_problem)).contains(app.getString(R.string.imp_problem_missing_columns)));
        assertEquals(View.VISIBLE, visibility(R.id.imp_choose_section));

        pick(Uri.parse(ImpTestSupport.DOCUMENTS + "不存在.csv"));
        waitUntil(() -> model().busy() == Busy.NONE && model().problem().orElse(null)
                == ImportWizardModel.Problem.READ_FAILED, "讀不到");
        assertTrue(onMain(() -> texts(R.id.imp_problem)).contains(app.getString(R.string.imp_problem_read_failed)));
    }

    /** 驗收截圖（{@code -e shots <前綴>}）：選檔、預覽（上半與按鈕）、完成並提示刪除 CSV、錯誤。 */
    @Test
    public void screens() throws Exception {
        String prefix = InstrumentationRegistry.getArguments().getString("shots");
        if (prefix == null) {
            return;
        }
        File out = new File(app.getExternalFilesDir(null), "shots/" + prefix);
        StringBuilder sb = new StringBuilder(SAMPLE);
        for (int i = 1; i <= 9; i++) {
            sb.append("站台 ").append(i).append(",https://s").append(i).append(".example.com/,user").append(i)
                    .append(",Fake-Pass-x").append(i).append(",\n");
        }
        Uri csv = vault.document("Chrome 密碼.csv", sb.toString());
        openFromMenu();
        shot(out, "60-import-choose");
        pick(csv);
        waitUntil(() -> model().step() == Step.PREVIEW, "預覽");
        shot(out, "61-import-preview");
        onMain(() -> {
            NestedScrollView scroll = resumed().findViewById(R.id.imp_scroll);
            scroll.fullScroll(View.FOCUS_DOWN);
            return null;
        });
        shot(out, "62-import-preview-bottom");
        onMain(() -> resumed().findViewById(R.id.imp_import).performClick());
        waitUntil(() -> model().step() == Step.DONE, "完成", 20);
        shot(out, "63-import-done");
        onMain(() -> resumed().findViewById(R.id.imp_again).performClick());
        pick(vault.document("other.csv", "title,login\nx,y\n"));
        waitUntil(() -> model().problem().isPresent(), "錯誤");
        shot(out, "64-import-problem");
    }

    /**
     * 「匯入中」的 ProgressOverlay 與耗時（{@code -e bigcsv 1}；模組規格 §2 的人手驗證項目）：正式 KDF 參數的假資料保管庫，
     * 匯入 2,000 筆，記下讀檔＋比對與匯入（存檔一次）的時間。
     */
    @Test
    public void bigImport() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        if (!"1".equals(args.getString("bigcsv"))) {
            return;
        }
        vault.unlockWith(List.of(new EntryDraft("舊的", "user7", "probe1234", "https://s7.example.com/", "")),
                KdfParams.DEFAULT);
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= 2000; i++) {
            sb.append("站台 ").append(i).append(",https://s").append(i).append(".example.com/login,user").append(i)
                    .append(",Fake-Pass-").append(i).append(",備註 ").append(i).append('\n');
        }
        Uri csv = vault.document("chrome-2000.csv", sb.toString());
        String prefix = args.getString("shots");
        File out = prefix == null ? null : new File(app.getExternalFilesDir(null), "shots/" + prefix);
        openFromMenu();

        long t0 = SystemClock.elapsedRealtime();
        pick(csv);
        if (out != null) {
            shotNow(out, "65-import-reading");
        }
        waitUntil(() -> model().step() == Step.PREVIEW, "預覽", 30);
        long read = SystemClock.elapsedRealtime() - t0;
        assertEquals(2000, (int) onMain(() -> model().total()));
        assertEquals(List.of(8), onMain(() -> model().duplicateLines()));

        long t1 = SystemClock.elapsedRealtime();
        onMain(() -> resumed().findViewById(R.id.imp_import).performClick());
        if (out != null) {
            ImpTestSupport.instr().waitForIdleSync();
            shotNow(out, "66-import-importing");
        }
        assertTrue(onMain(() -> model().busy() == Busy.IMPORTING || model().step() == Step.DONE));
        waitUntil(() -> model().step() == Step.DONE, "完成", 120);
        long imported = SystemClock.elapsedRealtime() - t1;
        assertEquals("已匯入 1999 筆", text(R.id.imp_done));
        // 只記筆數與毫秒（不是機密）
        Log.i("SVaultPerf", "匯入 2,000 筆：讀檔＋比對 " + read + " ms；匯入（正式 KDF、存檔一次）" + imported + " ms");
    }

    private void openFromMenu() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof HomeActivity, "首頁", 30);
        String title = app.getString(R.string.imp_page_title);
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
        waitUntil(() -> resumed() instanceof FeatureHostActivity && resumed().findViewById(R.id.imp_page) != null,
                "匯入頁");
    }

    private static void pick(Uri uri) {
        onMain(() -> {
            fragment().onPicked(uri);
            return null;
        });
    }

    private static ImportWizardFragment fragment() {
        return (ImportWizardFragment) ((FragmentActivity) resumed()).getSupportFragmentManager()
                .findFragmentById(R.id.sv_host_container);
    }

    private static ImportWizardModel model() {
        FragmentActivity a = (FragmentActivity) resumed();
        return new ViewModelProvider(a).get(ImportViewModel.class).model(a);
    }

    private static int visibility(int id) {
        return onMain(() -> resumed().findViewById(id).getVisibility());
    }

    private static String text(int id) {
        return onMain(() -> String.valueOf(((TextView) resumed().findViewById(id)).getText()));
    }

    private static List<String> texts(int id) {
        return descendants(resumed().findViewById(id)).stream().filter(v -> v instanceof TextView)
                .filter(v -> v.getVisibility() == View.VISIBLE)
                .map(v -> String.valueOf(((TextView) v).getText())).toList();
    }
}
