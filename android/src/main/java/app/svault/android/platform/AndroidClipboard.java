package app.svault.android.platform;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.ClipboardPort;
import java.util.Objects;

/**
 * 系統剪貼簿（core 的 {@link ClipboardPort}，T-19；只在主執行緒使用）。什麼時候清除由 {@link ClipboardGuard} 決定
 * （設定的秒數、手動上鎖與螢幕關閉時立即）。密碼標成敏感內容（Android 13 起系統的剪貼簿預覽不顯示明文，AC-05-3）。
 *
 * <p>Android 10 起 App 在背景讀不到剪貼簿，無法像 desktop 一樣確認「內容仍是那個密碼」；只比對看得到的標籤：
 * 讀得到且不是 S-Vault 放的（使用者已經複製了別的東西）就不動，讀不到時一律清除（AC-05-3，D-73）。
 *
 * <p>計時有兩份（T-28）：主執行緒的 {@code Handler}（行程還在時準時清）與 {@link ClipboardAlarm}（行程被系統結束時
 * 由系統叫起 {@link ClipboardClearReceiver} 清）；取消時兩份一起取消。
 */
public final class AndroidClipboard implements ClipboardPort {

    /** {@code ClipDescription.EXTRA_IS_SENSITIVE}（API 33）；更早的版本照官方建議用同一個字串。 */
    static final String EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE";
    /** 剪貼簿內容的標籤（不是內容本身），用來判斷剪貼簿上的是不是 S-Vault 放的。 */
    static final String LABEL = "S-Vault";

    private final Context context;
    private final ClipboardManager clipboard;

    /**
     * @param context 任一 Context（用 Application 的）
     */
    public AndroidClipboard(Context context) {
        this.context = context.getApplicationContext();
        this.clipboard = Objects.requireNonNull(context.getSystemService(ClipboardManager.class), "clipboard");
    }

    /**
     * @param context 任一 Context（用 Application 的）
     * @return 剪貼簿清除的計時器（給 {@link ClipboardGuard}，只用在剪貼簿）：主執行緒上的計時，另排 {@link ClipboardAlarm}
     *         備援。主執行緒以 {@code SystemClock.uptimeMillis} 計時，手機深度睡眠時不走；螢幕關閉時已經立即清除
     *         （AC-12-2 的上鎖），行程不在時由鬧鐘清
     */
    public static ClipboardGuard.Scheduler scheduler(Context context) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        return (delay, task) -> {
            Runnable once = task::run;
            main.postDelayed(once, delay.toMillis());
            ClipboardAlarm.schedule(app, delay);
            return () -> {
                main.removeCallbacks(once);
                ClipboardAlarm.cancel(app);
            };
        };
    }

    @Override
    public void copy(String value, boolean sensitive) {
        if (!sensitive) {
            // 換成不用清的內容：前一個行程留下、這個行程的計時不知道的備援鬧鐘也不再需要
            ClipboardAlarm.cancel(context);
        }
        ClipData clip = ClipData.newPlainText(LABEL, value == null ? "" : value);
        if (sensitive) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        clipboard.setPrimaryClip(clip);
    }

    @Override
    public void clearSecret(String secret) {
        ClipboardAlarm.cancel(context);
        ClipDescription d = clipboard.getPrimaryClipDescription();
        if (d != null && !LABEL.contentEquals(d.getLabel() == null ? "" : d.getLabel())) {
            return;
        }
        clipboard.clearPrimaryClip();
    }
}
