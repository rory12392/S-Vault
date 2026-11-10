package app.svault.android.shell.entries;

import androidx.annotation.Nullable;
import app.svault.android.shell.ext.PasswordFill;
import java.util.Arrays;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 編輯畫面交給功能模組的 {@link PasswordFill}（T-37，D-170）。一個畫面實例一份：{@code onDestroy}（含重建）時
 * {@link #close()}，之後這份發出去的回填都不再填入；每次回填還要畫面當下可以編輯（{@link Target#accepting()}：
 * 沒有上鎖、沒有在儲存、內容已載入、不是正在結束）。值不論填不填都清零；日誌只記模組代號與結果，不記內容。
 *
 * <p>不依賴 Android，JVM 單元測試 {@code PasswordFillsTest}。
 */
final class PasswordFills {

    private static final Logger LOG = Logger.getLogger(PasswordFills.class.getName());

    /** 密碼欄所在的畫面。 */
    interface Target {
        /** @return 現在可以填入（主執行緒） */
        boolean accepting();

        /**
         * 填進密碼欄（主執行緒）；之後由 {@link PasswordFills} 清零，不要保留陣列。
         *
         * @param value 密碼
         */
        void fill(char[] value);
    }

    private final Target target;
    private final BooleanSupplier onMainThread;
    private boolean open = true;

    /**
     * @param target       密碼欄所在的畫面
     * @param onMainThread 目前是不是主執行緒
     */
    PasswordFills(Target target, BooleanSupplier onMainThread) {
        this.target = target;
        this.onMainThread = onMainThread;
    }

    /**
     * @param moduleId 拿到回填的模組（只用在日誌）
     * @return 交給 {@code PasswordFieldAction.perform(Context, PasswordFill)} 的回填
     */
    PasswordFill handle(String moduleId) {
        return new Handle(moduleId);
    }

    /** 畫面結束（含重建）：之後一律不填入。主執行緒呼叫。 */
    void close() {
        open = false;
    }

    private boolean active() {
        return open && target.accepting();
    }

    private final class Handle implements PasswordFill {
        private final String moduleId;

        Handle(String moduleId) {
            this.moduleId = moduleId;
        }

        @Override
        public boolean fill(@Nullable char[] value) {
            if (value == null) {
                return false;
            }
            try {
                if (!onMainThread.getAsBoolean()) {
                    throw new IllegalStateException("PasswordFill 只能在主執行緒呼叫");
                }
                if (!active()) {
                    LOG.log(Level.FINE, "模組回填密碼欄：module={0} filled=false", moduleId);
                    return false;
                }
                target.fill(value);
                LOG.log(Level.FINE, "模組回填密碼欄：module={0} filled=true", moduleId);
                return true;
            } finally {
                Arrays.fill(value, '\0');
            }
        }

        @Override
        public boolean isActive() {
            return active();
        }

        @Override
        public String toString() {
            return "PasswordFill[" + moduleId + "]";
        }
    }
}
