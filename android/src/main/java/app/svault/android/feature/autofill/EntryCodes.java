package app.svault.android.feature.autofill;

import app.svault.core.module.ModulePreferences;

/**
 * 每筆建議驗證頁的 {@code PendingIntent} 代號（T-110，D-197；Codex M-04 第二批審查 1）。{@code PendingIntent} 只用代號與
 * Intent 本身（不含 extra）分辨，外殼的 {@code pageSender} 用 {@code FLAG_UPDATE_CURRENT}：代號撞到時，舊建議會指向新請求的
 * 參數（點 A App 上的舊建議，卻拿 B 的條目比對）。所以代號不從請求代號推算，改用記在本模組設定裡的遞增計數：範圍
 * {@code 0x40000000}–{@code 0x7FFFFFFF}（和整份驗證的請求代號、「搜尋保管庫」的負數不重疊），App 重新啟動也接著往下數，
 * 繞一圈要十億次以上。
 */
final class EntryCodes {

    static final long FIRST = 0x40000000L;
    static final long LAST = 0x7FFFFFFFL;
    /** {@code modules.autofill.entryCodeNext}：下一個要用的代號（只是計數，不含任何內容）。 */
    static final String KEY = "entryCodeNext";

    private EntryCodes() {
    }

    /**
     * @param prefs 本模組的設定
     * @return 這次要用的代號；之後的呼叫不會再拿到同一個（繞完一圈前）
     */
    static synchronized int next(ModulePreferences prefs) {
        long n = prefs.number(KEY).orElse(FIRST);
        if (n < FIRST || n > LAST) {
            n = FIRST;
        }
        prefs.put(KEY, n == LAST ? FIRST : n + 1);
        return (int) n;
    }
}
