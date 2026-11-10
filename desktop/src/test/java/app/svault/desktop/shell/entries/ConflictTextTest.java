package app.svault.desktop.shell.entries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.sync.ConflictView;
import app.svault.desktop.ui.DiffRow;
import java.text.MessageFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ResourceBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 衝突對照畫面的固定文字（測試逐字比對）與欄位、值的說法（T-15）。
 */
class ConflictTextTest {

    private static final ResourceBundle TEXT = ResourceBundle.getBundle("app.svault.desktop.shell.entries.messages");
    private static final ZoneId TAIPEI = ZoneOffset.ofHours(8);
    private static final Instant T = Instant.parse("2026-10-20T06:05:00Z");

    @Test
    @DisplayName("AC-08-2、AC-08-4：標題「第 n／N 筆」、快速按鈕、說明、未選完的提示逐字比對")
    void fixedTexts() {
        assertEquals("解決衝突（第 2／3 筆）",
                MessageFormat.format(TEXT.getString("conflict.title"), 2, 3));
        assertEquals("全部用這台裝置的", TEXT.getString("conflict.useThis"));
        assertEquals("全部用另一台裝置的", TEXT.getString("conflict.useOther"));
        assertEquals("沒被選的值會保存在這筆條目的歷史紀錄中。", TEXT.getString("conflict.hint"));
        assertEquals("還有欄位沒有選擇", TEXT.getString("conflict.missing"));
        assertEquals("下一筆", TEXT.getString("conflict.next"));
        assertEquals("完成並儲存", TEXT.getString("conflict.finish"));
        assertEquals("處理", TEXT.getString("banner.resolve"));
        assertEquals("有 {0} 筆衝突需要處理", TEXT.getString("banner.conflicts"));
    }

    @Test
    @DisplayName("每種欄位都有名稱，自訂欄位與附件帶名稱")
    void everyKindHasAName() {
        for (ConflictView.Kind kind : ConflictView.Kind.values()) {
            TEXT.getString("conflict.field." + kind.name());
        }
        assertEquals("密碼", ConflictDialog.fieldName(TEXT, row(ConflictView.Kind.PASSWORD, null)));
        assertEquals("自訂欄位「PIN」", ConflictDialog.fieldName(TEXT, row(ConflictView.Kind.CUSTOM, "PIN")));
        assertEquals("附件「說明.pdf」", ConflictDialog.fieldName(TEXT, row(ConflictView.Kind.ATTACHMENT, "說明.pdf")));
    }

    @Test
    @DisplayName("AC-08-1：每種值都有說法，附修改時間（裝置時區）；空白交給 DiffRow 顯示「（空白）」")
    void everyFormHasAText() {
        for (ConflictView.Shown.Form form : ConflictView.Shown.Form.values()) {
            TEXT.getString("conflict.value." + form.name());
        }
        DiffRow.Choice text = choice(ConflictView.Shown.Form.TEXT, "rory@pc", null);
        assertEquals("rory@pc", text.value());
        assertEquals("修改於 2026/10/20 14:05", text.time());
        assertEquals("", choice(ConflictView.Shown.Form.EMPTY, null, null).value());
        assertEquals("（已刪除）", choice(ConflictView.Shown.Form.REMOVED, null, null).value());
        assertEquals("家用 / 工作", choice(ConflictView.Shown.Form.GROUP, "家用 / 工作", null).value());
        assertEquals("（群組已不存在）", choice(ConflictView.Shown.Form.UNKNOWN_GROUP, null, null).value());
        assertEquals("標準圖示 12", choice(ConflictView.Shown.Form.ICON, "12", null).value());
        assertEquals("內容代碼 a1b2c3d4", choice(ConflictView.Shown.Form.ATTACHMENT, "a1b2c3d4", null).value());
        assertEquals("不到期", choice(ConflictView.Shown.Form.NEVER_EXPIRES, null, null).value());
        assertEquals("2026/10/20 14:05 到期", choice(ConflictView.Shown.Form.EXPIRES, null, T).value());
        assertNull(ConflictDialog.choice(TEXT, TAIPEI, new ConflictView.Shown(ConflictView.Shown.Form.EMPTY, null,
                null), null).time());
    }

    @Test
    @DisplayName("受保護的欄位（T-16）：只有值本身會遮罩，「（空白）」「（已刪除）」照常顯示")
    void onlyValuesAreMaskable() {
        assertTrue(choice(ConflictView.Shown.Form.TEXT, "1234", null).maskable());
        for (ConflictView.Shown.Form form : ConflictView.Shown.Form.values()) {
            if (form != ConflictView.Shown.Form.TEXT) {
                assertFalse(choice(form, "1", T).maskable(), form.name());
            }
        }
    }

    private static DiffRow.Choice choice(ConflictView.Shown.Form form, String text, Instant time) {
        return ConflictDialog.choice(TEXT, TAIPEI, new ConflictView.Shown(form, text, time), T);
    }

    private static ConflictView.Row row(ConflictView.Kind kind, String name) {
        ConflictView.Shown v = new ConflictView.Shown(ConflictView.Shown.Form.TEXT, "x", null);
        return new ConflictView.Row("k", kind, name, v, v, T, T, false);
    }
}
