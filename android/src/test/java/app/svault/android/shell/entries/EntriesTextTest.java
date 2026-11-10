package app.svault.android.shell.entries;

import static org.junit.Assert.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 固定文字逐字比對 {@code strings.xml}（兩平台一致）。
 */
public class EntriesTextTest {

    private static final Map<String, String> TEXT = load();

    @Test
    public void ac02_6_ac03_3_emptyAndNoResultTexts() {
        assertEquals("這個群組還沒有條目", TEXT.get("entries_empty_group_title"));
        assertEquals("建立你的第一筆條目", TEXT.get("entries_empty_vault_title"));
        assertEquals("新增條目", TEXT.get("entries_add"));
        assertEquals("找不到符合的條目", TEXT.get("entries_empty_search_title"));
        assertEquals("搜尋名稱、帳號或網址", TEXT.get("entries_search"));
        assertEquals("全部條目", TEXT.get("entries_all"));
        assertEquals("資源回收桶", TEXT.get("entries_recycle_bin"));
    }

    @Test
    public void ac02_3_ac05_1_dialogSaveAndCopyTexts() {
        assertEquals("刪除這筆條目？", TEXT.get("entries_confirm_delete_title"));
        assertEquals("「%1$s」會移到資源回收桶，之後可以還原。", TEXT.get("entries_confirm_delete_body"));
        assertEquals("捨棄修改？", TEXT.get("entries_discard_title"));
        assertEquals("你的修改還沒有儲存。", TEXT.get("entries_discard_body"));
        assertEquals("捨棄", TEXT.get("entries_discard_confirm"));
        assertEquals("繼續編輯", TEXT.get("entries_discard_keep"));
        assertEquals("名稱不能是空的", TEXT.get("entries_error_name"));
        assertEquals("已儲存", TEXT.get("entries_toast_saved"));
        // AC-05-1：秒數跟著設定（T-19；10–300，預設 30）
        assertEquals("已複製密碼，30 秒後清除", String.format(TEXT.get("entries_toast_copied_password"), 30L));
        assertEquals("已複製密碼，300 秒後清除", String.format(TEXT.get("entries_toast_copied_password"), 300L));
        assertEquals("已複製帳號", TEXT.get("entries_toast_copied_username"));
        assertEquals("群組裡的 %1$d 筆條目會一起移到資源回收桶，之後可以還原。",
                TEXT.get("entries_confirm_delete_group_body"));
    }

    private static Map<String, String> load() {
        try {
            String xml = new String(Files.readAllBytes(Paths.get("src", "main", "res", "values", "strings.xml")),
                    StandardCharsets.UTF_8);
            Map<String, String> out = new HashMap<>();
            Matcher m = Pattern.compile("<string name=\"([a-z0-9_]+)\">(.*?)</string>").matcher(xml);
            while (m.find()) {
                out.put(m.group(1), m.group(2).replace("\\'", "'"));
            }
            return out;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
