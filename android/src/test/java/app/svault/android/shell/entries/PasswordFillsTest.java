package app.svault.android.shell.entries;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import app.svault.android.shell.ext.PasswordFieldAction;
import app.svault.android.shell.ext.PasswordFill;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 編輯畫面交給模組的非同步回填（T-37，D-170）：畫面還在、可以編輯時才填入；重建、離開、上鎖、儲存中一律不填；
 * 不論填不填、在哪個執行緒呼叫，值都清零。
 */
public class PasswordFillsTest {

    private final List<String> filled = new ArrayList<>();
    private boolean accepting = true;
    private boolean main = true;
    private final PasswordFills fills = new PasswordFills(new PasswordFills.Target() {
        @Override
        public boolean accepting() {
            return accepting;
        }

        @Override
        public void fill(char[] value) {
            filled.add(new String(value));
        }
    }, () -> main);

    private static boolean zeroed(char[] value) {
        for (char c : value) {
            if (c != '\0') {
                return false;
            }
        }
        return true;
    }

    @Test
    public void fillsWhileScreenIsEditableAndZeroes() {
        PasswordFill fill = fills.handle("testfill");
        char[] value = "Gen-1234".toCharArray();
        assertTrue(fill.isActive());
        assertTrue(fill.fill(value));
        assertEquals(List.of("Gen-1234"), filled);
        assertTrue(zeroed(value));
        // 同一個回填可以再用（例如換了新結果再按一次）
        assertTrue(fill.fill("again".toCharArray()));
        assertEquals(2, filled.size());
    }

    @Test
    public void refusesAfterScreenClosedAndStillZeroes() {
        // 重建或離開：onDestroy 關閉，舊的回填不會填到新的畫面
        PasswordFill fill = fills.handle("testfill");
        fills.close();
        char[] value = "late".toCharArray();
        assertFalse(fill.isActive());
        assertFalse(fill.fill(value));
        assertTrue(filled.isEmpty());
        assertTrue(zeroed(value));
        // 關閉後才拿到的也一樣不能用
        assertFalse(fills.handle("testfill").fill("x".toCharArray()));
    }

    @Test
    public void refusesWhileNotAcceptingAndStillZeroes() {
        // 已上鎖、正在儲存、內容還沒載入、畫面正在結束
        PasswordFill fill = fills.handle("testfill");
        accepting = false;
        char[] value = "locked".toCharArray();
        assertFalse(fill.isActive());
        assertFalse(fill.fill(value));
        assertTrue(zeroed(value));
        accepting = true;
        assertTrue(fill.fill("ok".toCharArray()));
        assertEquals(List.of("ok"), filled);
    }

    @Test
    public void offMainThreadThrowsAndStillZeroes() {
        PasswordFill fill = fills.handle("testfill");
        main = false;
        char[] value = "bg".toCharArray();
        assertThrows(IllegalStateException.class, () -> fill.fill(value));
        assertTrue(zeroed(value));
        assertTrue(filled.isEmpty());
    }

    @Test
    public void nullDoesNothing() {
        assertFalse(fills.handle("testfill").fill(null));
        assertTrue(filled.isEmpty());
    }

    @Test
    public void targetFailureStillZeroes() {
        PasswordFills broken = new PasswordFills(new PasswordFills.Target() {
            @Override
            public boolean accepting() {
                return true;
            }

            @Override
            public void fill(char[] value) {
                throw new IllegalStateException("測試：欄位出錯");
            }
        }, () -> true);
        char[] value = "boom".toCharArray();
        assertThrows(IllegalStateException.class, () -> broken.handle("testfill").fill(value));
        assertTrue(zeroed(value));
    }

    @Test
    public void handleDoesNotExposeValueInToString() {
        PasswordFill fill = fills.handle("testfill");
        fill.fill("Secret-99".toCharArray());
        assertFalse(fill.toString().contains("Secret-99"));
    }

    @Test
    public void defaultAsyncPerformUsesSyncResult() {
        // 既有只實作同步版的模組：預設的非同步版立即回填，外殼清零（行為不變）
        char[] produced = "sync-1".toCharArray();
        PasswordFieldAction sync = new PasswordFieldAction() {
            @Override
            public int labelRes() {
                return 1;
            }

            @Override
            public char[] perform(Context context) {
                return produced;
            }
        };
        sync.perform(null, fills.handle("testsync"));
        assertEquals(List.of("sync-1"), filled);
        assertArrayEquals(new char[produced.length], produced);

        PasswordFieldAction none = new PasswordFieldAction() {
            @Override
            public int labelRes() {
                return 1;
            }

            @Override
            public char[] perform(Context context) {
                return null;
            }
        };
        none.perform(null, fills.handle("testsync"));
        assertEquals(1, filled.size());
    }
}
