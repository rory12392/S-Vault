package app.svault.android.feature.health;

import static app.svault.android.feature.health.HltTestSupport.descendants;
import static app.svault.android.feature.health.HltTestSupport.onMain;
import static app.svault.android.feature.health.HltTestSupport.resumed;
import static app.svault.android.feature.health.HltTestSupport.shot;
import static app.svault.android.feature.health.HltTestSupport.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.entries.EntryDetailActivity;
import app.svault.android.shell.entries.HomeActivity;
import app.svault.core.feature.health.BreachChecker;
import app.svault.core.feature.health.BreachReport;
import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.feature.health.HealthReportModel.Breach;
import app.svault.core.vault.EntryDraft;
import com.google.android.material.appbar.MaterialToolbar;
import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 健檢頁（T-53）：從首頁溢位選單開（真的模組，經 ServiceLoader 註冊）、摘要與三個區段、點列開條目詳細並返回、
 * 「檢查外洩」先確認、連線失敗的 Banner 而弱／重複照常、重建時沿用結果、TalkBack 標籤。只用假資料保管庫；外洩檢查換成連不到的
 * 本機位址（{@code 127.0.0.1:9}），不連真正的 HIBP。
 *
 * <p>帶 {@code -e shots <前綴>} 時另外截驗收圖（淺色／深色 × 預設與最大字體由 adb 切換後各跑一次）。
 * 帶 {@code -e realHibp 1} 時 {@link #realHibp()} 真的連到 Have I Been Pwned 檢查 {@code password123}（人手驗證項目，
 * 先徵得使用者同意才跑）。
 */
@RunWith(AndroidJUnit4.class)
public class HealthReportScreensTest {

    /** 沒有服務在聽的本機埠：連線被拒 → CONNECTION_FAILED。 */
    private static final String NOWHERE = "http://127.0.0.1:9";

    private SvaultApplication app;
    private HltTestSupport vault;
    private Supplier<BreachChecker> original;
    private Map<String, UUID> ids;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) HltTestSupport.instr().getTargetContext().getApplicationContext();
        original = HealthViewModel.checkers;
        HealthViewModel.checkers = () -> new BreachChecker(NOWHERE, 2_000);
        vault = new HltTestSupport(app);
        ids = vault.unlockWith(List.of(
                new EntryDraft("Bank", "rory", "password", "https://bank.example", ""),
                new EntryDraft("Mail", "rory", "Tr0ub4dor&3-Horse-Battery-Staple", "https://mail.example", ""),
                new EntryDraft("Shop", "rory", "Tr0ub4dor&3-Horse-Battery-Staple", "https://shop.example", ""),
                new EntryDraft("GitHub", "rory-dev", "k7#Vq9!mZp2&Lx4@Rw8", "https://github.com/login", "")));
    }

    @After
    public void tearDown() {
        HealthViewModel.checkers = original;
        vault.close();
    }

    /** AC-13-1：選單開頁 → 摘要、弱與重複的區段；點列開條目詳細，返回回到同一份結果（不重算）；TalkBack 標籤。 */
    @Test
    public void ac13_1_reportJumpsToEntry() {
        openFromMenu();
        assertEquals(app.getString(R.string.hlt_page_title),
                onMain(() -> String.valueOf(((MaterialToolbar) resumed().findViewById(R.id.sv_host_toolbar))
                        .getTitle())));
        assertEquals("1 筆", onMain(() -> summary(R.id.hlt_summary_weak)));
        assertEquals("2 筆", onMain(() -> summary(R.id.hlt_summary_reused)));
        assertEquals("尚未檢查", onMain(() -> summary(R.id.hlt_summary_breached)));
        List<String> texts = onMain(HealthReportScreensTest::sectionTexts);
        assertTrue(texts.toString(), texts.containsAll(List.of("弱密碼", "Bank", "強度：弱", "重複使用的密碼",
                "共用同一個密碼（2 筆）", "Mail", "Shop", "與 1 筆條目使用相同密碼")));
        assertFalse("強密碼不列出：" + texts, texts.contains("GitHub"));
        // 弱／重複的分析不連網：外洩仍是「尚未檢查」
        assertEquals(Breach.NOT_CHECKED, onMain(() -> model().breach()));

        // TalkBack：列的點擊動作讀成「跳到條目：Bank」
        assertEquals("跳到條目：Bank", onMain(() -> clickLabel(row("Bank"))));

        Activity page = onMain(HltTestSupport::resumed);
        onMain(() -> row("Bank").performClick());
        waitUntil(() -> resumed() instanceof EntryDetailActivity && "Bank".contentEquals(detailTitle()),
                "Bank 的條目詳細");
        onMain(() -> {
            ((FragmentActivity) resumed()).getOnBackPressedDispatcher().onBackPressed();
            return null;
        });
        waitUntil(() -> resumed() == page, "回到健檢頁");
        assertEquals("1 筆", onMain(() -> summary(R.id.hlt_summary_weak)));
        assertNotNull(ids.get("Bank"));
    }

    /** AC-13-2、AC-13-4：先確認（取消時不連線）；確認後連不到 → Banner「無法連線，稍後再試」，弱／重複照常。 */
    @Test
    public void ac13_2_confirmThenConnectionFailed() {
        openFromMenu();
        onMain(() -> resumed().findViewById(R.id.hlt_check_breach).performClick());
        waitUntil(() -> fragment().confirm != null && fragment().confirm.isShowing(), "確認對話框");
        assertEquals(app.getString(R.string.hlt_breach_confirm_ok), onMain(() -> String.valueOf(
                fragment().confirm.getButton(DialogInterface.BUTTON_POSITIVE).getText())));
        onMain(() -> fragment().confirm.getButton(DialogInterface.BUTTON_NEGATIVE).performClick());
        waitUntil(() -> fragment().confirm == null, "取消後關閉");
        assertEquals(Breach.NOT_CHECKED, onMain(() -> model().breach()));

        onMain(() -> resumed().findViewById(R.id.hlt_check_breach).performClick());
        waitUntil(() -> fragment().confirm != null, "確認對話框");
        onMain(() -> fragment().confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
        waitUntil(() -> model().breach() == Breach.CONNECTION_FAILED, "連線失敗", 20);
        assertEquals(View.VISIBLE, (int) onMain(() -> resumed().findViewById(R.id.hlt_breach_failed).getVisibility()));
        assertTrue(onMain(() -> texts(resumed().findViewById(R.id.hlt_breach_failed))).contains("無法連線，稍後再試"));
        assertEquals("無法連線", onMain(() -> summary(R.id.hlt_summary_breached)));
        assertTrue(onMain(HealthReportScreensTest::sectionTexts).contains("Bank"));
        assertTrue(onMain(() -> resumed().findViewById(R.id.hlt_check_breach).isEnabled()));
    }

    /** T-53：重建（旋轉、換主題、改字體）時沿用 Activity 範圍的結果，不重算。 */
    @Test
    public void recreateKeepsResult() {
        openFromMenu();
        HealthReportModel before = onMain(HealthReportScreensTest::model);
        Activity old = onMain(HltTestSupport::resumed);
        onMain(() -> {
            old.recreate();
            return null;
        });
        waitUntil(() -> resumed() != old && resumed().findViewById(R.id.hlt_page) != null
                && "1 筆".equals(summary(R.id.hlt_summary_weak)), "重建後的頁面");
        assertTrue(onMain(() -> model() == before));
        assertTrue(onMain(HealthReportScreensTest::sectionTexts).contains("Bank"));
    }

    /** 驗收截圖（{@code -e shots <前綴>}）：報告、確認對話框、連線失敗。 */
    @Test
    public void screens() {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            return;
        }
        File out = new File(app.getExternalFilesDir(null), "shots/" + prefix);
        openFromMenu();
        shot(out, "50-health-report", null);
        onMain(() -> resumed().findViewById(R.id.hlt_check_breach).performClick());
        waitUntil(() -> fragment().confirm != null && fragment().confirm.isShowing(), "確認對話框");
        AlertDialog dialog = onMain(() -> fragment().confirm);
        shot(out, "51-health-confirm", dialog);
        onMain(() -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
        waitUntil(() -> model().breach() == Breach.CONNECTION_FAILED, "連線失敗", 20);
        shot(out, "52-health-failed", null);
    }

    /**
     * 人手驗證（規格 §2 的 T-51 手機項目；{@code -e realHibp 1}，先徵得使用者同意）：不換位址，真的連到 Have I Been Pwned，
     * 假資料保管庫裡密碼 {@code password123} 的條目列在「外洩的密碼」、次數數百萬。
     */
    @Test
    public void realHibp() throws Exception {
        Bundle args = InstrumentationRegistry.getArguments();
        if (!"1".equals(args.getString("realHibp"))) {
            return;
        }
        HealthViewModel.checkers = original;
        assertNull("不能設定 SVAULT_HIBP_BASE_URL", System.getenv("SVAULT_HIBP_BASE_URL"));
        vault.unlockWith(List.of(
                new EntryDraft("Bank", "rory", "password123", "https://bank.example", ""),
                new EntryDraft("GitHub", "rory-dev", "k7#Vq9!mZp2&Lx4@Rw8", "https://github.com/login", "")));
        openFromMenu();
        onMain(() -> resumed().findViewById(R.id.hlt_check_breach).performClick());
        waitUntil(() -> fragment().confirm != null && fragment().confirm.isShowing(), "確認對話框");
        onMain(() -> fragment().confirm.getButton(DialogInterface.BUTTON_POSITIVE).performClick());
        waitUntil(() -> model().breach() != Breach.RUNNING, "外洩檢查結束", 60);
        assertEquals(Breach.COMPLETED, onMain(() -> model().breach()));
        List<HealthReportModel.Row> hits = onMain(() -> model().breachRows());
        assertEquals(1, hits.size());
        assertEquals("Bank", hits.get(0).title());
        assertTrue("次數應有數百萬：" + hits.get(0).count(), hits.get(0).count() > 1_000_000);
        String prefix = args.getString("shots");
        if (prefix != null) {
            shot(new File(app.getExternalFilesDir(null), "shots/" + prefix), "53-health-breached", null);
        }
        // 只記次數（不是機密），給人手驗證的紀錄
        android.util.Log.i("SVaultTest", "HIBP password123 次數：" + hits.get(0).count() + "；狀態 "
                + BreachReport.Status.COMPLETED);
    }

    private void openFromMenu() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof HomeActivity, "首頁");
        String title = app.getString(R.string.hlt_page_title);
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
        waitUntil(() -> resumed() instanceof FeatureHostActivity && resumed().findViewById(R.id.hlt_page) != null
                && model().analysis() == HealthReportModel.Analysis.READY, "健檢頁算完", 30);
    }

    private static HealthReportFragment fragment() {
        return (HealthReportFragment) ((FragmentActivity) resumed()).getSupportFragmentManager()
                .findFragmentById(R.id.sv_host_container);
    }

    private static HealthReportModel model() {
        FragmentActivity a = (FragmentActivity) resumed();
        return new ViewModelProvider(a).get(HealthViewModel.class).model(a);
    }

    private static String summary(int rowId) {
        return String.valueOf(((TextView) resumed().findViewById(rowId).findViewById(R.id.settings_row_value))
                .getText());
    }

    private static List<String> sectionTexts() {
        return texts(resumed().findViewById(R.id.hlt_sections));
    }

    private static List<String> texts(View root) {
        return descendants(root).stream().filter(v -> v instanceof TextView && v.isShown())
                .map(v -> String.valueOf(((TextView) v).getText())).toList();
    }

    /** @return 名稱是 {@code title} 的條目列（settings_row 的根） */
    private static View row(String title) {
        for (View v : descendants(resumed().findViewById(R.id.hlt_sections))) {
            if (v.getId() == R.id.settings_row_title && title.contentEquals(((TextView) v).getText())) {
                return (View) v.getParent().getParent();
            }
        }
        throw new AssertionError("找不到列：" + title);
    }

    private static String clickLabel(View row) {
        AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
        for (AccessibilityNodeInfo.AccessibilityAction a : info.getActionList()) {
            if (a.getId() == AccessibilityNodeInfo.ACTION_CLICK) {
                return String.valueOf(a.getLabel());
            }
        }
        return null;
    }

    private static CharSequence detailTitle() {
        return ((MaterialToolbar) resumed().findViewById(R.id.detail_toolbar)).getTitle();
    }
}
