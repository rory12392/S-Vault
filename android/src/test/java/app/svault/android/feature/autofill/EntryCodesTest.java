package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.svault.core.module.MapPreferences;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** Codex M-04 第二批審查 1：每筆建議的 PendingIntent 代號不重複、跨重新啟動接著數、和其他驗證的代號不重疊。 */
public class EntryCodesTest {

    @Test
    public void codesNeverRepeatAndSurviveRestart() {
        MapPreferences prefs = new MapPreferences();
        Set<Integer> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int code = EntryCodes.next(prefs);
            assertTrue("不和整份驗證（請求代號）、搜尋保管庫（負數）重疊：" + code, code >= EntryCodes.FIRST);
            assertTrue("不重複：" + code, seen.add(code));
        }
        // 模擬 App 重新啟動：計數在設定裡，接著往下數
        MapPreferences reloaded = new MapPreferences();
        reloaded.values.putAll(prefs.values);
        assertTrue(seen.add(EntryCodes.next(reloaded)));
    }

    @Test
    public void wrapsAtTheEndAndRepairsBadValues() {
        MapPreferences prefs = new MapPreferences();
        prefs.put(EntryCodes.KEY, EntryCodes.LAST);
        assertEquals(Integer.MAX_VALUE, EntryCodes.next(prefs));
        assertEquals((int) EntryCodes.FIRST, EntryCodes.next(prefs));
        prefs.put(EntryCodes.KEY, 5L);
        assertEquals("被改成範圍外的值時從頭開始", (int) EntryCodes.FIRST, EntryCodes.next(prefs));
    }
}
