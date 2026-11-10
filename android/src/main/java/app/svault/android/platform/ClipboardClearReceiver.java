package app.svault.android.platform;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * {@link ClipboardAlarm} 的鬧鐘響時清除剪貼簿（T-28）；App 行程已被系統結束時，系統會為它重新啟動行程。
 * 清除的條件和主執行緒的計時相同（{@link AndroidClipboard#clearSecret}）：讀得到且不是 S-Vault 放的就不動，
 * 讀不到（App 在背景）一律清除（AC-05-3，D-73）。不匯出、沒有 intent-filter，只有 App 自己的 {@code PendingIntent} 叫得到。
 */
public final class ClipboardClearReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        new AndroidClipboard(context).clearSecret(null);
    }
}
