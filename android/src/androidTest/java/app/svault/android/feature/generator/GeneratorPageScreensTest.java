package app.svault.android.feature.generator;

import static app.svault.android.feature.generator.GenTestSupport.descendants;
import static app.svault.android.feature.generator.GenTestSupport.instr;
import static app.svault.android.feature.generator.GenTestSupport.onMain;
import static app.svault.android.feature.generator.GenTestSupport.resumed;
import static app.svault.android.feature.generator.GenTestSupport.settle;
import static app.svault.android.feature.generator.GenTestSupport.shot;
import static app.svault.android.feature.generator.GenTestSupport.waitUntil;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.FeatureHostActivity;
import app.svault.android.shell.entries.HomeActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.StrengthMeter;
import app.svault.core.feature.generator.GeneratorSettings;
import app.svault.core.module.ModulePreferences;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.slider.Slider;
import java.io.File;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 產生器頁（T-42）：從首頁溢位選單開（真的模組，經 ServiceLoader 註冊）、結果與強度、「複製」走外殼的
 * 剪貼簿並提示秒數、選項不合法、通行片語、重建時沿用結果、上次的選項記在 {@code modules.generator.*}、TalkBack 標籤。
 * 只用假資料保管庫；結束時把產生器的設定還原成測試前的值、清空剪貼簿（上鎖時外殼會清）。
 *
 * <p>帶 {@code -e shots <前綴>} 時另外截驗收圖（DESIGN §7：淺色／深色 × 預設與最大字體由 adb 切換後各跑一次）。
 */
@RunWith(AndroidJUnit4.class)
public class GeneratorPageScreensTest {

    private SvaultApplication app;
    private GenTestSupport vault;
    private ModulePreferences prefs;
    private GeneratorSettings before;

    @Before
    public void setUp() throws Exception {
        app = (SvaultApplication) instr().getTargetContext().getApplicationContext();
        prefs = onMain(() -> ModuleHost.of(app).preferences(GeneratorAndroidFeature.ID));
        before = onMain(() -> GeneratorSettings.load(prefs));
        onMain(() -> {
            GeneratorSettings.DEFAULTS.save(prefs);
            return null;
        });
        vault = new GenTestSupport(app);
        vault.unlockEmptyVault();
    }

    @After
    public void tearDown() {
        onMain(() -> {
            before.save(prefs);
            return null;
        });
        vault.close();
    }

    @Test
    public void ac04_pageFromMenuCopiesAndRemembers() {
        openFromMenu();
        assertEquals(app.getString(R.string.gen_page_title),
                onMain(() -> String.valueOf(((MaterialToolbar) resumed().findViewById(R.id.sv_host_toolbar)).getTitle())));

        // AC-04-1：預設 20 字元；AC-04-4：強度在背景算完後顯示（預設選項一定是「強」）
        String first = onMain(GeneratorPageScreensTest::resultText);
        assertEquals(GeneratorSettings.DEFAULT_LENGTH, first.length());
        waitUntil(() -> meter().getLevel() == StrengthMeter.Level.STRONG, "強度「強」");

        // 「複製」：剪貼簿是畫面上的密碼，提示用外殼回傳的秒數
        onMain(() -> resumed().findViewById(R.id.gen_copy).performClick());
        waitUntil(() -> copiedHintShown(), "已複製的提示");
        assertEquals(first, onMain(GeneratorPageScreensTest::clipboardText));
        assertTrue("外殼的 ClipboardGuard 排了清除", app.clipboard().hasPendingSecret());

        // 重新產生：換一個結果
        onMain(() -> resumed().findViewById(R.id.gen_regenerate).performClick());
        assertNotEquals(first, onMain(GeneratorPageScreensTest::resultText));

        // 重建（旋轉、換主題）：結果與選項沿用 Activity 範圍的 ViewModel
        setLengthByUser(32);
        String beforeRecreate = onMain(GeneratorPageScreensTest::resultText);
        assertEquals(32, beforeRecreate.length());
        Activity old = onMain(GenTestSupport::resumed);
        onMain(() -> {
            old.recreate();
            return null;
        });
        waitUntil(() -> resumed() != old && resumed().findViewById(R.id.gen_result) != null, "重建後的頁面");
        assertEquals(beforeRecreate, onMain(GeneratorPageScreensTest::resultText));
        assertEquals(32, onMain(() -> (int) ((Slider) resumed().findViewById(R.id.gen_length_slider)).getValue())
                .intValue());

        // 上次的選項存在 modules.generator.*
        assertEquals(32, onMain(() -> GeneratorSettings.load(prefs).password().length()).intValue());
    }

    @Test
    public void ac04_invalidAndPassphrase() {
        openFromMenu();
        // AC-04-1：四種字元都不選 → 「至少選一種字元」，不能複製與重新產生
        for (int id : new int[] {R.id.gen_class_upper, R.id.gen_class_lower, R.id.gen_class_digit, R.id.gen_class_symbol}) {
            onMain(() -> resumed().findViewById(id).performClick());
        }
        settle();
        assertTrue(onMain(() -> resumed().findViewById(R.id.gen_invalid).isShown()));
        assertFalse(onMain(() -> resumed().findViewById(R.id.gen_copy).isEnabled()));
        assertFalse(onMain(() -> resumed().findViewById(R.id.gen_regenerate).isEnabled()));
        assertEquals(StrengthMeter.Level.NONE, onMain(GeneratorPageScreensTest::meter).getLevel());

        // AC-04-2：通行片語（預設 5 個單字、連字號）
        onMain(() -> resumed().findViewById(R.id.gen_mode_passphrase).performClick());
        settle();
        assertFalse(onMain(() -> resumed().findViewById(R.id.gen_invalid).isShown()));
        assertTrue(onMain(() -> resumed().findViewById(R.id.gen_passphrase_options).isShown()));
        assertFalse(onMain(() -> resumed().findViewById(R.id.gen_password_options).isShown()));
        String phrase = onMain(GeneratorPageScreensTest::resultText);
        assertTrue(phrase, phrase.matches("[a-z-]+(-[a-z-]+)+"));
        assertTrue(onMain(() -> resumed().findViewById(R.id.gen_copy).isEnabled()));
        waitUntil(() -> meter().getLevel() != StrengthMeter.Level.NONE, "通行片語的強度");
        assertEquals(GeneratorSettings.Mode.PASSPHRASE, onMain(() -> GeneratorSettings.load(prefs).mode()));
    }

    @Test
    public void talkBackLabels() {
        openFromMenu();
        waitUntil(() -> meter().getLevel() == StrengthMeter.Level.STRONG, "強度「強」");
        onMain(() -> {
            Activity a = resumed();
            assertEquals(app.getString(R.string.gen_result_regenerate),
                    String.valueOf(a.findViewById(R.id.gen_regenerate).getContentDescription()));
            assertEquals(app.getString(R.string.gen_length_label),
                    String.valueOf(a.findViewById(R.id.gen_length_slider).getContentDescription()));
            assertEquals(app.getString(R.string.gen_words_label),
                    String.valueOf(a.findViewById(R.id.gen_words).getContentDescription()));
            assertEquals(app.getString(R.string.sv_strength_accessible, app.getString(R.string.sv_strength_strong)),
                    String.valueOf(meter().getContentDescription()));
            assertEquals(R.id.gen_result, a.findViewById(R.id.gen_result_label).getLabelFor());
            // 數字欄的標籤看得到、TalkBack 讀得到（不靠會被截斷的浮動標籤）
            assertTrue(a.findViewById(R.id.gen_length_label).isShown());
            assertEquals(R.id.gen_length, a.findViewById(R.id.gen_length_label).getLabelFor());
            // 每個可點的元件都有讀得出來的名稱（文字或描述）
            for (View v : descendants(a.findViewById(R.id.gen_page))) {
                if (v.isShown() && v.isClickable() && (v instanceof MaterialButton || v instanceof MaterialCheckBox)) {
                    CharSequence name = v.getContentDescription() != null ? v.getContentDescription()
                            : ((TextView) v).getText();
                    assertTrue(v.toString(), name != null && name.length() > 0);
                }
            }
            return null;
        });
    }

    @Test
    public void screens() {
        Bundle args = InstrumentationRegistry.getArguments();
        String prefix = args.getString("shots");
        if (prefix == null) {
            return;
        }
        File out = new File(app.getExternalFilesDir(null), "shots/" + prefix);
        openFromMenu();
        waitUntil(() -> meter().getLevel() == StrengthMeter.Level.STRONG, "強度「強」");
        shot(out, "40-generator-page");
        onMain(() -> resumed().findViewById(R.id.gen_copy).performClick());
        waitUntil(GeneratorPageScreensTest::copiedHintShown, "已複製的提示");
        shot(out, "41-generator-copied");
        for (int id : new int[] {R.id.gen_class_upper, R.id.gen_class_lower, R.id.gen_class_digit, R.id.gen_class_symbol}) {
            onMain(() -> resumed().findViewById(id).performClick());
        }
        shot(out, "42-generator-invalid");
        onMain(() -> resumed().findViewById(R.id.gen_mode_passphrase).performClick());
        waitUntil(() -> meter().getLevel() != StrengthMeter.Level.NONE, "通行片語的強度");
        shot(out, "43-generator-passphrase");
    }

    private void openFromMenu() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof HomeActivity, "首頁");
        String title = app.getString(R.string.gen_page_title);
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
        waitUntil(() -> resumed() instanceof FeatureHostActivity && resumed().findViewById(R.id.gen_result) != null,
                "產生器頁");
    }

    /** 和使用者拖完滑桿一樣：值改變（fromUser）後放開（存檔）。 */
    private static void setLengthByUser(int length) {
        onMain(() -> {
            View page = resumed().findViewById(R.id.gen_page);
            TextView field = page.findViewById(R.id.gen_length);
            field.requestFocus();
            field.setText(String.valueOf(length));
            field.onEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            return null;
        });
        settle();
    }

    private static String resultText() {
        return String.valueOf(((TextView) resumed().findViewById(R.id.gen_result)).getText());
    }

    private static StrengthMeter meter() {
        return resumed().findViewById(R.id.gen_strength);
    }

    private static String clipboardText() {
        ClipboardManager cm = (ClipboardManager) resumed().getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = cm.getPrimaryClip();
        return clip == null || clip.getItemCount() == 0 ? "" : String.valueOf(clip.getItemAt(0).getText());
    }

    /** 「已複製密碼，n 秒後清除」：n 是外殼 {@code copySecret} 回傳的秒數（設定值，10–300）。 */
    private static boolean copiedHintShown() {
        String pattern = resumed().getString(R.string.gen_copied, 0).replace("0", "\\d+");
        for (View v : descendants(resumed().getWindow().getDecorView())) {
            if (v instanceof TextView t && v.isShown() && String.valueOf(t.getText()).matches(pattern)) {
                return true;
            }
        }
        return false;
    }
}
