package app.svault.core.shell;

import app.svault.core.module.ModuleClipboard;
import app.svault.core.session.ClipboardGuard;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * 把外殼的 {@link ClipboardGuard} 包成給功能模組的 {@link ModuleClipboard}（T-34，兩平台的 {@code ModuleHost} 都經這裡）。
 * 模組只拿到「複製密碼」與「複製一般文字」：拿不到 {@code ClipboardGuard} 本身（不能提前清除別人的密碼、查有沒有密碼在
 * 剪貼簿），也沒有讀取剪貼簿或改秒數的方法。
 *
 * <p>和外殼共用同一個 {@code ClipboardGuard}，所以清除規則完全相同：上鎖時外殼呼叫的 {@code clearNow()} 也清掉模組複製的
 * 密碼；模組複製的內容取代外殼複製的密碼時，外殼的計時取消（反過來也是）。
 *
 * <p>只記錄模組代號與是不是密碼（{@code FINE}），不記內容。
 */
public final class ModuleClipboards {

    private static final Logger LOG = Logger.getLogger(ModuleClipboards.class.getName());
    /** 同 {@code ScopedPreferences} 的模組代號格式。 */
    private static final Pattern MODULE_ID = Pattern.compile("[a-z0-9]+");

    private ModuleClipboards() {
    }

    /**
     * @param moduleId   呼叫端模組的代號（只用在日誌）
     * @param guard      外殼複製密碼用的同一個實例
     * @param onUiThread 目前是不是 UI 執行緒（desktop {@code Platform::isFxApplicationThread}、android 比對主執行緒的
     *                   {@code Looper}）；{@code ClipboardGuard} 不做同步，只能在 UI 執行緒使用
     * @return 給這個模組的剪貼簿
     * @throws IllegalArgumentException 模組代號格式不對
     */
    public static ModuleClipboard forModule(String moduleId, ClipboardGuard guard, BooleanSupplier onUiThread) {
        if (moduleId == null || !MODULE_ID.matcher(moduleId).matches()) {
            throw new IllegalArgumentException("模組代號格式不對");
        }
        Objects.requireNonNull(guard, "guard");
        Objects.requireNonNull(onUiThread, "onUiThread");
        return new ModuleClipboard() {
            @Override
            public Duration copySecret(String secret) {
                Objects.requireNonNull(secret, "secret");
                requireUiThread();
                LOG.log(Level.FINE, "模組複製：module={0} secret=true", moduleId);
                return guard.copySecret(secret);
            }

            @Override
            public void copy(String text) {
                Objects.requireNonNull(text, "text");
                requireUiThread();
                LOG.log(Level.FINE, "模組複製：module={0} secret=false", moduleId);
                guard.copy(text);
            }

            private void requireUiThread() {
                if (!onUiThread.getAsBoolean()) {
                    throw new IllegalStateException("剪貼簿只能在 UI 執行緒使用：module=" + moduleId);
                }
            }
        };
    }
}
