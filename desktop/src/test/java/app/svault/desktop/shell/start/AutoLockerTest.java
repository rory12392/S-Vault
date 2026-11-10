package app.svault.desktop.shell.start;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.svault.core.session.LockSignalPort;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.MutableClock;
import app.svault.core.storage.Settings;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * desktop 自動上鎖的判斷（T-18；AC-12-1）：閒置分鐘數、設定立即套用、Windows 鎖定螢幕。計時器與事件過濾要 JavaFX，
 * 這裡直接呼叫 {@link AutoLocker#check()}／{@link AutoLocker#onSignal()}。
 */
class AutoLockerTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T02:00:00Z"));
    private final LiveSettings live = LiveSettings.inMemory(Settings.defaults());
    private final AtomicBoolean unlocked = new AtomicBoolean(true);
    private final AtomicInteger locks = new AtomicInteger();
    private final AutoLocker auto = new AutoLocker(live, clock, LockSignalPort.NONE, unlocked::get,
            locks::incrementAndGet);

    @Test
    @DisplayName("AC-12-1：解鎖中閒置滿 5 分鐘（預設）上鎖；4 分 59 秒時還不會")
    void ac12_1_locksAfterDefaultIdle() {
        clock.advance(Duration.ofMinutes(4).plusSeconds(59));
        auto.check();
        assertEquals(0, locks.get());

        clock.advanceSeconds(1);
        auto.check();
        assertEquals(1, locks.get());
    }

    @Test
    @DisplayName("AC-12-1：有操作就重新計時")
    void ac12_1_activityRestartsTimer() {
        clock.advance(Duration.ofMinutes(4));
        auto.touch();
        clock.advance(Duration.ofMinutes(4));
        auto.check();

        assertEquals(0, locks.get());
    }

    @Test
    @DisplayName("AC-12-1：設成不自動上鎖（0）時閒置多久都不上鎖")
    void ac12_1_offNeverLocks() {
        live.update(s -> Settings.AUTO_LOCK_DESKTOP_MINUTES.write(s, 0));
        clock.advance(Duration.ofHours(8));
        auto.check();

        assertEquals(0, locks.get());
    }

    @Test
    @DisplayName("AC-12-1：設定頁改分鐘數立即套用，從改的那一刻重新計時")
    void ac12_1_settingAppliesImmediately() {
        clock.advance(Duration.ofMinutes(3));
        live.update(s -> Settings.AUTO_LOCK_DESKTOP_MINUTES.write(s, 1));
        auto.check();
        assertEquals(0, locks.get(), "改短了不會立刻上鎖");

        clock.advance(Duration.ofMinutes(1));
        auto.check();
        assertEquals(1, locks.get());
    }

    @Test
    @DisplayName("AC-12-1：Windows 鎖定螢幕時立即上鎖，不看閒置設定")
    void ac12_1_sessionLockLocksImmediately() {
        live.update(s -> Settings.AUTO_LOCK_DESKTOP_MINUTES.write(s, 0));

        auto.onSignal();

        assertEquals(1, locks.get());
    }

    @Test
    @DisplayName("沒有解鎖（或正在上鎖）時不重複上鎖")
    void nothingWhenLocked() {
        unlocked.set(false);
        clock.advance(Duration.ofHours(1));

        auto.check();
        auto.onSignal();

        assertEquals(0, locks.get());
    }
}
