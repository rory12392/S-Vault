package app.svault.android.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.core.storage.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

/**
 * Android 背景上鎖的判斷（T-18；AC-12-2）：切到背景 n 秒、0＝立即、螢幕關閉、畫面重建不算切到背景。
 * 計時器換成手動觸發的假計時器，時間用可撥的時鐘。
 */
public class BackgroundLockTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T02:00:00Z"));
    private final FakeTimer timer = new FakeTimer();
    private Duration limit = Duration.ofSeconds(30);
    private boolean unlocked = true;
    private int locks;
    private final BackgroundLock lock = new BackgroundLock(clock, () -> limit, () -> unlocked, () -> {
        locks++;
        unlocked = false;
    }, timer);

    @Test
    public void ac12_2_locksThirtySecondsAfterGoingToBackground() {
        lock.activityStarted();
        lock.activityStopped(false, false);

        assertEquals(Duration.ofSeconds(30), timer.delay);
        clock.advanceSeconds(30);
        timer.fire();
        assertEquals(1, locks);
    }

    @Test
    public void d192_ownPickerDoesNotLockImmediately() {
        // D-192：設成「立即」時，S-Vault 自己開的系統選擇器期間不算背景，選完回來照常解鎖中
        limit = Duration.ZERO;
        lock.activityStarted();
        lock.expectExternalPicker();
        lock.activityStopped(false, false);
        assertEquals(0, locks);
        assertEquals(BackgroundLock.PICKER_GRACE, timer.delay);
        clock.advanceSeconds(20);
        lock.activityStarted();
        assertEquals(0, locks);

        // 回來之後恢復原本的規則：下一次離開就立即上鎖
        lock.activityStopped(false, false);
        assertEquals(1, locks);
    }

    @Test
    public void d192_pickerGraceIsAtMostTwoMinutes() {
        limit = Duration.ZERO;
        lock.activityStarted();
        lock.expectExternalPicker();
        lock.activityStopped(false, false);
        clock.advanceSeconds(121);
        timer.fire();
        assertEquals(1, locks);
    }

    @Test
    public void d192_pickerThatNeverOpenedDoesNotGrantLaterGrace() {
        // Codex 待辦：選擇器沒開成功時，寬限不能留給之後一般的切到背景
        limit = Duration.ZERO;
        lock.activityStarted();
        lock.expectExternalPicker();
        clock.advanceSeconds(11);
        lock.activityStopped(false, false);
        assertEquals(1, locks);
    }

    @Test
    public void d192_longerSettingIsNotShortened() {
        // 設定的秒數比寬限長時照設定（寬限只放寬、不縮短）
        limit = Duration.ofMinutes(5);
        lock.activityStarted();
        lock.expectExternalPicker();
        lock.activityStopped(false, false);
        assertEquals(Duration.ofMinutes(5), timer.delay);
    }

    @Test
    public void ac12_2_returningBeforeLimitKeepsUnlocked() {
        lock.activityStarted();
        lock.activityStopped(false, false);
        clock.advanceSeconds(29);

        lock.activityStarted();

        assertTrue(timer.cancelled);
        assertEquals(0, locks);
    }

    @Test
    public void ac12_2_returningAfterLimitLocksEvenIfTimerWasLate() {
        // 休眠（Doze）時計時器可能晚到：回到前景時以時鐘判斷
        lock.activityStarted();
        lock.activityStopped(false, false);
        clock.advanceSeconds(45);

        lock.activityStarted();

        assertEquals(1, locks);
    }

    @Test
    public void ac12_2_zeroLocksImmediately() {
        limit = Duration.ZERO;
        lock.activityStarted();

        lock.activityStopped(false, false);

        assertEquals(1, locks);
        assertFalse(timer.scheduled);
    }

    @Test
    public void ac12_2_screenOffLocksImmediately() {
        lock.activityStarted();

        lock.screenOff();

        assertEquals(1, locks);
    }

    @Test
    public void ac12_2_configurationChangeIsNotBackground() {
        // 旋轉、深色模式切換：舊畫面停止、新畫面開始，中間不算切到背景（設成立即上鎖時也不會鎖）
        limit = Duration.ZERO;
        lock.activityStarted();
        lock.activityStopped(true, false);
        lock.activityStarted();

        assertEquals(0, locks);
    }

    @Test
    public void ac12_2_switchingBetweenOwnScreensIsNotBackground() {
        limit = Duration.ZERO;
        lock.activityStarted();
        // 下一個畫面先開始、上一個才停止
        lock.activityStarted();
        lock.activityStopped(false, false);

        assertEquals(0, locks);
    }

    @Test
    public void ac12_2_afterLockingInBackgroundReturnDoesNotLockAgain() {
        // 切到背景時已經鎖了；之後在背景中解鎖（例如由其他流程），回到前景不能再當成「離開太久」又鎖一次
        limit = Duration.ZERO;
        lock.activityStarted();
        lock.activityStopped(false, false);
        assertEquals(1, locks);
        unlocked = true;

        lock.activityStarted();

        assertEquals(1, locks);
    }

    @Test
    public void ac12_2_clearTaskSwitchIsNotBackground() {
        // CLEAR_TASK 換畫面（例如解鎖後進首頁）：舊畫面結束並停止後，新畫面稍後才開始。設成立即上鎖也不能鎖
        limit = Duration.ZERO;
        lock.activityStarted();
        lock.activityStopped(false, true);
        assertEquals(0, locks);
        clock.advance(Duration.ofMillis(300));

        lock.activityStarted();

        assertEquals(0, locks);
        assertTrue(timer.cancelled);
    }

    @Test
    public void ac12_2_leavingByBackLocksAfterGrace() {
        // 按返回離開最後一個畫面（畫面結束）也是離開 App：寬限過後照樣上鎖
        limit = Duration.ZERO;
        lock.activityStarted();
        lock.activityStopped(false, true);

        assertEquals(BackgroundLock.FINISH_GRACE, timer.delay);
        clock.advance(BackgroundLock.FINISH_GRACE);
        timer.fire();
        assertEquals(1, locks);
    }

    @Test
    public void nothingWhenAlreadyLocked() {
        unlocked = false;
        lock.activityStarted();
        lock.activityStopped(false, false);
        lock.screenOff();
        clock.advanceSeconds(60);
        timer.fire();

        assertEquals(0, locks);
    }

    /** 手動觸發的計時器。 */
    private static final class FakeTimer implements BackgroundLock.Timer {
        Runnable task;
        Duration delay;
        boolean scheduled;
        boolean cancelled;

        @Override
        public void schedule(Runnable t, Duration d) {
            task = t;
            delay = d;
            scheduled = true;
        }

        @Override
        public void cancel() {
            cancelled = true;
            task = null;
        }

        void fire() {
            if (task != null) {
                task.run();
            }
        }
    }
}
