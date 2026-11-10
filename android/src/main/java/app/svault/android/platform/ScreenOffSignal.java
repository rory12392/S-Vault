package app.svault.android.platform;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;
import androidx.core.content.ContextCompat;
import app.svault.core.session.LockSignalPort;

/**
 * 螢幕關閉時立即上鎖的訊號（T-18；AC-12-2）：在 App 執行期間以程式註冊 {@code ACTION_SCREEN_OFF} 的接收器（這個系統廣播
 * 不能在 manifest 宣告，也就不用改 manifest）。訊號在主執行緒送達。
 */
public final class ScreenOffSignal implements LockSignalPort {

    private static final String TAG = "SVault";

    private final Context context;
    private BroadcastReceiver receiver;

    /**
     * @param context App 的 Context（接收器跟著 App 的生命週期）
     */
    public ScreenOffSignal(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean start(Runnable onSignal) {
        if (receiver != null) {
            return true;
        }
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                    onSignal.run();
                }
            }
        };
        try {
            // 只收系統廣播：其他 App 送不進來（NOT_EXPORTED；系統廣播照常送達）
            ContextCompat.registerReceiver(context, receiver, new IntentFilter(Intent.ACTION_SCREEN_OFF),
                    ContextCompat.RECEIVER_NOT_EXPORTED);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "無法註冊螢幕關閉的通知：" + e.getClass().getName());
            receiver = null;
            return false;
        }
    }

    @Override
    public void stop() {
        if (receiver != null) {
            context.unregisterReceiver(receiver);
            receiver = null;
        }
    }
}
