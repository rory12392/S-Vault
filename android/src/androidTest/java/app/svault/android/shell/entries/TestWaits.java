package app.svault.android.shell.entries;

import static app.svault.android.shell.entries.ScreenShots.instr;
import static app.svault.android.shell.entries.ScreenShots.resumed;
import static app.svault.android.shell.entries.ScreenShots.settle;

import android.os.SystemClock;
import java.util.function.Supplier;

/** 儀器測試共用：在主執行緒取值、等條件成立（T-19 起的新測試用；舊測試各自有同樣的私有版本）。 */
final class TestWaits {

    private TestWaits() {
    }

    static <T> T onMain(Supplier<T> get) {
        Object[] box = new Object[1];
        instr().runOnMainSync(() -> box[0] = get.get());
        @SuppressWarnings("unchecked")
        T t = (T) box[0];
        return t;
    }

    static void waitUntil(Supplier<Boolean> done, String what) {
        for (int i = 0; i < 100; i++) {
            Boolean ok;
            try {
                ok = onMain(done);
            } catch (AssertionError | RuntimeException e) {
                // 換畫面途中沒有前景的 Activity
                ok = false;
            }
            if (Boolean.TRUE.equals(ok)) {
                settle();
                return;
            }
            SystemClock.sleep(100);
        }
        String front;
        try {
            front = onMain(() -> resumed().getClass().getSimpleName());
        } catch (AssertionError | RuntimeException e) {
            front = "沒有";
        }
        throw new AssertionError("等不到：" + what + "（前景：" + front + "）");
    }
}
