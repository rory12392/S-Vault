package app.svault.android.platform;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import java.time.Duration;
import java.util.Objects;

/**
 * 剪貼簿的備援清除（T-28；AC-05-3）：主執行緒的計時在 App 行程裡，行程被系統結束（從最近使用的 App 滑掉、記憶體
 * 不足）時跟著消失。複製密碼時另排一個 AlarmManager 鬧鐘，時間到由系統叫起 {@link ClipboardClearReceiver}，
 * 行程已經不在也會清。
 *
 * <ul>
 *   <li><b>不精確的鬧鐘</b>（{@code setAndAllowWhileIdle}）：不需要權限；精確鬧鐘在 Android 12 起要
 *       {@code SCHEDULE_EXACT_ALARM}（使用者要另外允許）或 {@code USE_EXACT_ALARM}（只給鬧鐘、行事曆類 App）。
 *       系統可能延後最多約設定秒數的 0.75 倍；行程還在時主執行緒的計時照常準時清，鬧鐘只是備援。</li>
 *   <li><b>不存密碼</b>：系統保存的只有「什麼時候叫哪個接收器」，Intent 沒有任何 extra。</li>
 *   <li>整個 App 只有一個鬧鐘：同一個 {@code PendingIntent}，再次排定會取代前一個。</li>
 * <li>「強制停止」會取消 App 所有的鬧鐘，那時不會清（系統的限制）。</li>
 * </ul>
 */
public final class ClipboardAlarm {

    private ClipboardAlarm() {
    }

    /**
     * 排定（或改排）備援清除。
     *
     * @param context 任一 Context
     * @param delay   多久後清除
     */
    public static void schedule(Context context, Duration delay) {
        AlarmManager alarms = Objects.requireNonNull(context.getSystemService(AlarmManager.class), "alarm");
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay.toMillis(), intent(context, 0));
    }

    /**
     * 取消備援清除（S-Vault 已經清掉、或剪貼簿上已換成不用清的內容）；沒有排定時不做事。
     *
     * @param context 任一 Context
     */
    public static void cancel(Context context) {
        PendingIntent pending = intent(context, PendingIntent.FLAG_NO_CREATE);
        if (pending != null) {
            Objects.requireNonNull(context.getSystemService(AlarmManager.class), "alarm").cancel(pending);
            pending.cancel();
        }
    }

    /**
     * @param context 任一 Context
     * @return 還有排定、還沒取消的備援清除（測試用）
     */
    public static boolean isScheduled(Context context) {
        return intent(context, PendingIntent.FLAG_NO_CREATE) != null;
    }

    // 明確指定接收器、沒有 extra；FLAG_IMMUTABLE 讓叫用的一方不能改內容
    private static PendingIntent intent(Context context, int flags) {
        Intent i = new Intent(context, ClipboardClearReceiver.class);
        return PendingIntent.getBroadcast(context, 0, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT | flags);
    }
}
