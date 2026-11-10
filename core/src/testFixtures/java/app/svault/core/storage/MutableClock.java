package app.svault.core.storage;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * 測試用、可以往前撥的時鐘（core、desktop、android 的測試共用；T-13 從三份複本整理到 testFixtures）。
 *
 * <p>不依賴任何測試框架；儀器測試的執行緒也會讀，所以時間欄位是 {@code volatile}。
 */
public final class MutableClock extends Clock {

    /** 沒有指定時區時用的時區（使用者所在地，備份檔名等以當地時間命名的測試用得到）。 */
    public static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private volatile Instant now;
    private final ZoneId zone;

    /**
     * @param start 起始時間；時區為 {@link #TAIPEI}
     */
    public MutableClock(Instant start) {
        this(start, TAIPEI);
    }

    /**
     * @param start 起始時間
     * @param zone  時區
     */
    public MutableClock(Instant start, ZoneId zone) {
        this.now = start;
        this.zone = zone;
    }

    /** @param seconds 往前撥的秒數 */
    public void advanceSeconds(long seconds) {
        now = now.plusSeconds(seconds);
    }

    /** @param amount 往前撥的時間 */
    public void advance(Duration amount) {
        now = now.plus(amount);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId newZone) {
        return new MutableClock(now, newZone);
    }

    @Override
    public Instant instant() {
        return now;
    }
}
