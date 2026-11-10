package app.svault.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MutableClock;
import app.svault.core.storage.Settings;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自動上鎖的時間規則（T-18；AC-12-1、AC-12-2）：設定值換成時間長度，以及閒置計時。
 */
class AutoLockTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T02:00:00Z"));

    @Test
    @DisplayName("AC-12-1：PC 預設閒置 5 分鐘上鎖；可設 1–60 分鐘，0＝不自動上鎖")
    void ac12_1_desktopLimit() {
        assertEquals(Optional.of(Duration.ofMinutes(5)), AutoLock.desktopIdleLimit(Settings.defaults()));
        assertEquals(Optional.of(Duration.ofMinutes(1)), AutoLock.desktopIdleLimit(
                Settings.AUTO_LOCK_DESKTOP_MINUTES.write(Settings.defaults(), 1)));
        assertEquals(Optional.of(Duration.ofMinutes(60)), AutoLock.desktopIdleLimit(
                Settings.AUTO_LOCK_DESKTOP_MINUTES.write(Settings.defaults(), 60)));
        assertEquals(Optional.empty(), AutoLock.desktopIdleLimit(
                Settings.AUTO_LOCK_DESKTOP_MINUTES.write(Settings.defaults(), 0)));
    }

    @Test
    @DisplayName("AC-12-2：Android 預設切到背景 30 秒上鎖；0＝立即")
    void ac12_2_androidLimit() {
        assertEquals(Duration.ofSeconds(30), AutoLock.androidBackgroundLimit(Settings.defaults()));
        assertEquals(Duration.ZERO, AutoLock.androidBackgroundLimit(
                Settings.AUTO_LOCK_ANDROID_SECONDS.write(Settings.defaults(), 0)));
        assertEquals(Duration.ofHours(1), AutoLock.androidBackgroundLimit(
                Settings.AUTO_LOCK_ANDROID_SECONDS.write(Settings.defaults(), 3600)));
    }

    @Test
    @DisplayName("AC-12-1：閒置滿 5 分鐘才到期；期間有操作就重新計時")
    void ac12_1_idleTimerResetsOnActivity() {
        IdleTimer idle = new IdleTimer(clock);
        Duration limit = Duration.ofMinutes(5);

        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        assertFalse(idle.expired(limit));
        idle.touch();
        clock.advance(Duration.ofMinutes(4));
        assertFalse(idle.expired(limit), "操作後重新計時");
        clock.advance(Duration.ofMinutes(1));
        assertTrue(idle.expired(limit));
        assertEquals(Duration.ofMinutes(5), idle.idleFor());
    }

    @Test
    @DisplayName("AC-12-2：上限 0 一律到期（切到背景立即上鎖）")
    void ac12_2_zeroLimitExpiresImmediately() {
        IdleTimer idle = new IdleTimer(clock);

        assertTrue(idle.expired(Duration.ZERO));
    }

    @Test
    @DisplayName("時鐘往回撥（例如手動改時間）時不當成閒置很久，也不會一直不到期")
    void clockGoingBackRestartsTimer() {
        IdleTimer idle = new IdleTimer(clock);
        clock.advance(Duration.ofMinutes(-10));

        assertFalse(idle.expired(Duration.ofMinutes(5)));
        clock.advance(Duration.ofMinutes(5));
        assertTrue(idle.expired(Duration.ofMinutes(5)), "從撥回後的時間重新計時");
    }
}
