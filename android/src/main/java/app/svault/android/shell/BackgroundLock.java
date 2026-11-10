package app.svault.android.shell;

import app.svault.core.session.IdleTimer;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Android 的自動上鎖判斷（T-18；AC-12-2、Architecture §10）：App 的畫面全部停止（切到背景）後滿設定的秒數上鎖，0＝立即；
 * 螢幕關閉時立即上鎖。換畫面（下一個先開始、上一個才停止）與畫面重建（旋轉、深色模式切換）不算切到背景。
 *
 * <p>不依賴 Android 類別（JVM 單元測試直接用）：畫面的開始與停止由 {@link AutoLockCallbacks} 轉進來，計時器由
 * {@link Timer} 提供。計時器在系統休眠（Doze）時可能晚到，所以回到前景時再以時鐘判斷一次。只在主執行緒呼叫。
 */
public final class BackgroundLock {

    /** 最後一個畫面是「結束」（不是被蓋住）時的最短等待：換畫面的空檔不算離開 App。 */
    public static final Duration FINISH_GRACE = Duration.ofSeconds(1);

    /** S-Vault 自己開系統選擇器時，這次離開最多寬限多久（D-192）。 */
    public static final Duration PICKER_GRACE = Duration.ofMinutes(2);

    /** 延遲執行（正式程式是主執行緒的 {@code Handler}）。 */
    public interface Timer {

        /**
         * @param task  到時要執行的工作（取代之前排的）
         * @param delay 延遲
         */
        void schedule(Runnable task, Duration delay);

        /** 取消排著的工作。 */
        void cancel();
    }

    private final IdleTimer away;
    private final Supplier<Duration> limit;
    private final BooleanSupplier unlocked;
    private final Runnable lock;
    private final Timer timer;
    private int started;
    private boolean background;
    /** 這次離開前景後多久上鎖（離開當下讀的設定值，或結束畫面的寬限）。 */
    private Duration due = Duration.ZERO;
    /** 開系統選擇器前的「打招呼」（{@link #expectExternalPicker}）；這之後 {@link #PICKER_START} 內離開才算數，回到前景就清掉。 */
    private final IdleTimer pickerExpected;
    private boolean externalPicker;

    /** 打招呼之後多久內離開前景才算是那個選擇器（選擇器沒開成功時，寬限不留給之後一般的切到背景）。 */
    static final Duration PICKER_START = Duration.ofSeconds(10);

    /**
     * @param clock    時間來源
     * @param limit    切到背景多久上鎖（設定頁改了立即套用，每次切到背景時讀）
     * @param unlocked 目前有解鎖的保管庫（主執行緒上的狀態，不問背景存檔時被鎖住的 session）
     * @param lock     上鎖
     * @param timer    延遲執行
     */
    public BackgroundLock(Clock clock, Supplier<Duration> limit, BooleanSupplier unlocked, Runnable lock,
            Timer timer) {
        this.away = new IdleTimer(Objects.requireNonNull(clock, "clock"));
        this.pickerExpected = new IdleTimer(clock);
        this.limit = Objects.requireNonNull(limit, "limit");
        this.unlocked = Objects.requireNonNull(unlocked, "unlocked");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /** 一個畫面開始（{@code onStart}）：從背景回來時取消計時，已經超過時間就上鎖。 */
    public void activityStarted() {
        started++;
        if (started == 1) {
            externalPicker = false;
        }
        if (started == 1 && background) {
            background = false;
            timer.cancel();
            if (unlocked.getAsBoolean() && away.expired(due)) {
                lock.run();
            }
        }
    }

    // 鎖了就不再算「在背景」：之後在背景中解鎖時，回到前景不會被當成離開太久又鎖一次
    private void lockNow() {
        background = false;
        timer.cancel();
        lock.run();
    }

    /**
     * 一個畫面停止（{@code onStop}）。
     *
     * @param changingConfigurations 畫面正要重建（{@code Activity.isChangingConfigurations()}）
     * @param finishing              畫面正在結束（{@code Activity.isFinishing()}）
     */
    public void activityStopped(boolean changingConfigurations, boolean finishing) {
        started = Math.max(0, started - 1);
        if (started > 0 || changingConfigurations) {
            return;
        }
        background = true;
        away.touch();
        due = limit.get();
        if (externalPicker && !pickerExpected.expired(PICKER_START) && due.compareTo(PICKER_GRACE) < 0) {
            // D-192：使用者在 S-Vault 裡按了「選擇檔案」，系統選擇器蓋在上面不算離開；最多寬限 2 分鐘，超過照樣上鎖
            due = PICKER_GRACE;
        }
        if (finishing && due.compareTo(FINISH_GRACE) < 0) {
            // 以 CLEAR_TASK 換畫面（解鎖後進首頁、上鎖導回解鎖畫面）時，舊畫面先結束並停止、新畫面稍後才開始：
            // 給一點寬限，期間有畫面開始就不算離開（按返回離開最後一個畫面時，寬限過後照樣上鎖）
            due = FINISH_GRACE;
        }
        if (!unlocked.getAsBoolean()) {
            return;
        }
        if (due.isZero()) {
            lockNow();
        } else {
            timer.schedule(this::expire, due);
        }
    }

    /**
     * 接下來要開 S-Vault 自己的系統選擇器（例：匯入的「選擇 CSV」，D-192）：下一次離開前景最多寬限
     * {@link #PICKER_GRACE}，不照「立即」上鎖。回到前景時清掉；螢幕關閉照樣立即上鎖。
     */
    public void expectExternalPicker() {
        externalPicker = true;
        pickerExpected.touch();
    }

    /** 螢幕關閉：解鎖中就立即上鎖，不看背景秒數（AC-12-2）。 */
    public void screenOff() {
        if (unlocked.getAsBoolean()) {
            lockNow();
        }
    }

    private void expire() {
        if (background && unlocked.getAsBoolean() && away.expired(due)) {
            lockNow();
        }
    }
}
