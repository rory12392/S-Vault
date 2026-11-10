package app.svault.core.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 閒置計時（T-18；AC-12-1、AC-12-2）：記住最後一次操作的時間，判斷閒置是否超過上限。desktop 用它算「閒置 n 分鐘」，
 * android 用它算「切到背景 n 秒」（切到背景時 {@link #touch()}）。
 *
 * <p>以注入的 {@link Clock} 計時（CONVENTIONS §2）：電腦睡眠醒來時時間已經過去，下一次檢查就會到期。任何執行緒都可以
 * 呼叫（平台的輸入事件與計時器在不同執行緒）。
 */
public final class IdleTimer {

    private final Clock clock;
    private volatile Instant last;

    /**
     * @param clock 時間來源；建立時開始計時
     */
    public IdleTimer(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.last = clock.instant();
    }

    /** 有操作（或切到背景的那一刻）：重新計時。 */
    public void touch() {
        last = clock.instant();
    }

    /**
     * @return 距離上一次 {@link #touch()} 多久；時鐘往回撥時從撥回後的時間重新計時
     */
    public Duration idleFor() {
        Instant now = clock.instant();
        if (now.isBefore(last)) {
            // 使用者把系統時間往回撥：不然要等撥回的那段時間過完才會到期
            last = now;
        }
        return Duration.between(last, now);
    }

    /**
     * @param limit 閒置上限；{@link Duration#ZERO} 表示一律到期（android「切到背景立即上鎖」）
     * @return 已閒置滿 {@code limit}
     */
    public boolean expired(Duration limit) {
        return idleFor().compareTo(limit) >= 0;
    }
}
