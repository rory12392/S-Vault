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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.svault.android.R;
import app.svault.android.SvaultApplication;
import app.svault.android.shell.entries.EntryDetailActivity;
import app.svault.android.shell.entries.EntryEditActivity;
import app.svault.android.shell.entries.HomeActivity;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.ui.SecureWindows;
import app.svault.android.ui.StrengthMeter;
import app.svault.core.feature.generator.GeneratorSettings;
import app.svault.core.module.ModulePreferences;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.checkbox.MaterialCheckBox;
import java.io.File;
import java.util.UUID;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 編輯畫面「產生」的底部 sheet（T-44，AC-04-1～AC-04-4 的 android 編輯畫面部分）：真的 GEN 模組
 * （ServiceLoader 註冊）經 T-37 的 {@code PasswordFill} 回填。「使用這個密碼」把 sheet 上的密碼填進密碼欄、「取消」不改、
 * 選項不合法時不能使用、通行片語、sheet 視窗有 {@code FLAG_SECURE}、重建時 sheet 消失、上鎖時跟著關閉、TalkBack 標籤。
 * 只用假資料保管庫；結束時把產生器的設定還原成測試前的值。
 *
 * <p>帶 {@code -e shots <前綴>} 時另外截驗收圖（淺色／深色 × 預設與最大字體由 adb 切換後各跑一次）。
 */
@RunWith(AndroidJUnit4.class)
public class GeneratorSheetScreensTest {

    private static final String OLD_PASSWORD = "old-pass-1";

    private SvaultApplication app;
    private GenTestSupport vault;
    private ModulePreferences prefs;
    private GeneratorSettings before;
    private UUID entry;

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
        entry = vault.unlockWithEntry(OLD_PASSWORD);
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
    public void ac04_useFillsAndCancelKeeps() {
        openEdit();
        // 按鈕文字（TalkBack 也讀這個）是模組的「產生」
        assertEquals(app.getString(R.string.gen_action_generate),
                onMain(() -> String.valueOf(generateButton().getText())));

        // 取消：不改密碼欄
        openSheet();
        assertTrue("sheet 的視窗防截圖", onMain(() -> SecureWindows.isSecure(sheet().getDialog().getWindow())));
        String first = onMain(GeneratorSheetScreensTest::sheetResult);
        assertEquals(GeneratorSettings.DEFAULT_LENGTH, first.length());
        waitUntil(() -> meter().getLevel() == StrengthMeter.Level.STRONG, "強度「強」");
        onMain(() -> sheetView(R.id.gen_cancel).performClick());
        waitUntil(() -> sheet() == null, "sheet 關閉");
        assertEquals(OLD_PASSWORD, password());

        // 使用這個密碼：密碼欄換成 sheet 上的密碼；每次打開都是新的結果
        openSheet();
        String shown = onMain(GeneratorSheetScreensTest::sheetResult);
        assertNotEquals(first, shown);
        onMain(() -> sheetView(R.id.gen_regenerate).performClick());
        String regenerated = onMain(GeneratorSheetScreensTest::sheetResult);
        assertNotEquals(shown, regenerated);
        onMain(() -> sheetView(R.id.gen_use).performClick());
        waitUntil(() -> sheet() == null, "sheet 關閉");
        assertEquals(regenerated, password());
        assertTrue(onMain(() -> resumed() instanceof EntryEditActivity));
    }

    @Test
    public void ac04_invalidAndPassphrase() {
        openEdit();
        openSheet();
        // AC-04-1：四種字元都不選 → 「至少選一種字元」，「使用這個密碼」不能按
        for (int id : new int[] {R.id.gen_class_upper, R.id.gen_class_lower, R.id.gen_class_digit, R.id.gen_class_symbol}) {
            onMain(() -> sheetView(id).performClick());
        }
        settle();
        assertTrue(onMain(() -> sheetView(R.id.gen_invalid).isShown()));
        assertFalse(onMain(() -> sheetView(R.id.gen_use).isEnabled()));
        assertEquals(StrengthMeter.Level.NONE, onMain(GeneratorSheetScreensTest::meter).getLevel());

        // AC-04-2：通行片語（預設 5 個單字、連字號）→ 使用這個密碼
        onMain(() -> sheetView(R.id.gen_mode_passphrase).performClick());
        settle();
        assertTrue(onMain(() -> sheetView(R.id.gen_use).isEnabled()));
        String phrase = onMain(GeneratorSheetScreensTest::sheetResult);
        assertTrue(phrase, phrase.matches("[a-z-]+(-[a-z-]+)+"));
        waitUntil(() -> meter().getLevel() != StrengthMeter.Level.NONE, "通行片語的強度");
        onMain(() -> sheetView(R.id.gen_use).performClick());
        waitUntil(() -> sheet() == null, "sheet 關閉");
        assertEquals(phrase, password());
        // 選項和產生器頁共用 modules.generator.*
        assertEquals(GeneratorSettings.Mode.PASSPHRASE, onMain(() -> GeneratorSettings.load(prefs).mode()));
    }

    @Test
    public void recreateDropsSheet() {
        openEdit();
        openSheet();
        Activity old = onMain(GenTestSupport::resumed);
        // 重建（同換主題、旋轉）：sheet 不還原，使用者再按一次「產生」
        onMain(() -> {
            old.recreate();
            return null;
        });
        waitUntil(() -> resumed() != old && resumed() instanceof EntryEditActivity
                && OLD_PASSWORD.equals(shownPassword()), "重建後的編輯畫面");
        settle();
        assertNull(onMain(GeneratorSheetScreensTest::sheet));
        assertEquals(OLD_PASSWORD, password());
        openSheet();
        String shown = onMain(GeneratorSheetScreensTest::sheetResult);
        onMain(() -> sheetView(R.id.gen_use).performClick());
        waitUntil(() -> shown.equals(shownPassword()), "重建後回填");
    }

    @Test
    public void lockClosesSheet() {
        openEdit();
        openSheet();
        onMain(() -> {
            app.lockNow();
            return null;
        });
        waitUntil(() -> !(resumed() instanceof EntryEditActivity), "上鎖後離開編輯畫面");
        assertNull(onMain(GeneratorSheetScreensTest::sheet));
    }

    @Test
    public void talkBackLabels() {
        openEdit();
        openSheet();
        waitUntil(() -> meter().getLevel() == StrengthMeter.Level.STRONG, "強度「強」");
        onMain(() -> {
            View root = sheet().requireView();
            TextView title = root.findViewById(R.id.gen_sheet_title);
            assertEquals(app.getString(R.string.gen_sheet_title), String.valueOf(title.getText()));
            assertTrue(title.isAccessibilityHeading());
            assertEquals(app.getString(R.string.gen_use),
                    String.valueOf(((TextView) root.findViewById(R.id.gen_use)).getText()));
            assertEquals(app.getString(R.string.gen_cancel),
                    String.valueOf(((TextView) root.findViewById(R.id.gen_cancel)).getText()));
            assertEquals(app.getString(R.string.gen_result_regenerate),
                    String.valueOf(root.findViewById(R.id.gen_regenerate).getContentDescription()));
            assertEquals(app.getString(R.string.sv_strength_accessible, app.getString(R.string.sv_strength_strong)),
                    String.valueOf(meter().getContentDescription()));
            assertEquals(R.id.gen_result, root.findViewById(R.id.gen_result_label).getLabelFor());
            assertEquals(R.id.gen_length, root.findViewById(R.id.gen_length_label).getLabelFor());
            for (View v : descendants(root)) {
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
        openEdit();
        shot(out, "50-edit-generate");
        openSheet();
        waitUntil(() -> meter().getLevel() == StrengthMeter.Level.STRONG, "強度「強」");
        shot(out, "51-sheet", dialog());
        for (int id : new int[] {R.id.gen_class_upper, R.id.gen_class_lower, R.id.gen_class_digit, R.id.gen_class_symbol}) {
            onMain(() -> sheetView(id).performClick());
        }
        shot(out, "52-sheet-invalid", dialog());
        onMain(() -> sheetView(R.id.gen_mode_passphrase).performClick());
        waitUntil(() -> meter().getLevel() != StrengthMeter.Level.NONE, "通行片語的強度");
        shot(out, "53-sheet-passphrase", dialog());
        onMain(() -> sheetView(R.id.gen_use).performClick());
        waitUntil(() -> sheet() == null, "sheet 關閉");
        shot(out, "54-filled");
    }

    /** 首頁 → 用模組的條目導覽開詳細 → 「編輯」。 */
    private void openEdit() {
        app.startActivity(new Intent(app, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitUntil(() -> resumed() instanceof HomeActivity, "首頁");
        onMain(() -> {
            ModuleHost.of(resumed()).navigator(resumed()).openEntry(entry);
            return null;
        });
        waitUntil(() -> resumed() instanceof EntryDetailActivity, "條目詳細");
        onMain(() -> resumed().findViewById(R.id.detail_edit).performClick());
        waitUntil(() -> resumed() instanceof EntryEditActivity && OLD_PASSWORD.equals(shownPassword()), "編輯畫面");
    }

    private static void openSheet() {
        onMain(() -> generateButton().performClick());
        waitUntil(() -> sheet() != null && sheet().getDialog() != null && sheet().getDialog().isShowing()
                && sheet().getView() != null, "產生的 sheet");
    }

    private static MaterialButton generateButton() {
        ViewGroup actions = resumed().findViewById(R.id.edit_password_actions);
        String label = resumed().getString(R.string.gen_action_generate);
        for (int i = 0; i < actions.getChildCount(); i++) {
            if (actions.getChildAt(i) instanceof MaterialButton b && label.contentEquals(b.getText())) {
                return b;
            }
        }
        throw new AssertionError("編輯畫面沒有「產生」");
    }

    private static GeneratorSheet sheet() {
        Activity a = resumed();
        if (!(a instanceof FragmentActivity fa)) {
            return null;
        }
        return (GeneratorSheet) fa.getSupportFragmentManager().findFragmentByTag(GeneratorSheet.TAG);
    }

    private static Dialog dialog() {
        Dialog d = onMain(() -> sheet().getDialog());
        assertNotNull(d);
        return d;
    }

    private static <V extends View> V sheetView(int id) {
        return sheet().requireView().findViewById(id);
    }

    private static String sheetResult() {
        return String.valueOf(((TextView) sheetView(R.id.gen_result)).getText());
    }

    private static StrengthMeter meter() {
        return sheetView(R.id.gen_strength);
    }

    private static String password() {
        return onMain(GeneratorSheetScreensTest::shownPassword);
    }

    private static String shownPassword() {
        return String.valueOf(((TextView) resumed().findViewById(R.id.edit_password)).getText());
    }
}
