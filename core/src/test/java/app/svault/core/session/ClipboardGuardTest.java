package app.svault.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 剪貼簿的清除規則（T-19；AC-05-1～AC-05-4）：秒數讀設定、計時到了請平台清除、複製別的內容後不再清、上鎖時立即清。
 * 「清除的條件」（PC 比對內容、android 一律清除）在平台的 {@link ClipboardPort}，這裡只確認什麼時候要求清除。
 */
class ClipboardGuardTest {

    private final FakePort port = new FakePort();
    private final FakeScheduler scheduler = new FakeScheduler();
    private final LiveSettings live = LiveSettings.inMemory(Settings.defaults());
    private final ClipboardGuard guard = new ClipboardGuard(port, scheduler, live::current);

    @Test
    @DisplayName("AC-05-1：複製密碼時標成敏感內容，回傳的秒數是設定值（預設 30）")
    void ac05_1_copySecretUsesSettingSeconds() {
        Duration shown = guard.copySecret("s3cret");

        assertEquals(Duration.ofSeconds(30), shown);
        assertEquals(List.of("copy:s3cret:sensitive"), port.calls);
        assertEquals(List.of(Duration.ofSeconds(30)), scheduler.delays);
        assertTrue(guard.hasPendingSecret());
    }

    @Test
    @DisplayName("AC-05-1：設定改成 10 或 300 秒時，下一次複製就用新的秒數")
    void ac05_1_settingChangeAppliesToNextCopy() {
        live.update(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 10));
        assertEquals(Duration.ofSeconds(10), guard.copySecret("a"));

        live.update(s -> Settings.CLIPBOARD_CLEAR_SECONDS.write(s, 300));
        assertEquals(Duration.ofSeconds(300), guard.copySecret("b"));

        assertEquals(List.of(Duration.ofSeconds(10), Duration.ofSeconds(300)), scheduler.delays);
    }

    @Test
    @DisplayName("AC-05-2、AC-05-3：時間到了請平台清除這個密碼（條件由平台判斷），之後不再算有密碼在剪貼簿")
    void ac05_2_clearsWhenTimerFires() {
        guard.copySecret("s3cret");

        scheduler.fireAll();

        assertEquals(List.of("copy:s3cret:sensitive", "clear:s3cret"), port.calls);
        assertFalse(guard.hasPendingSecret());
    }

    @Test
    @DisplayName("AC-05-2：再複製一次密碼時取消上一次的計時，只清最後一個")
    void ac05_2_secondCopyReplacesTimer() {
        guard.copySecret("first");
        guard.copySecret("second");

        assertEquals(1, scheduler.cancelled);
        scheduler.fireAll();

        assertEquals(List.of("copy:first:sensitive", "copy:second:sensitive", "clear:second"), port.calls);
    }

    @Test
    @DisplayName("AC-05-2：之後由 S-Vault 複製帳號等內容（不清除），密碼已經不在剪貼簿：取消計時，不清掉帳號")
    void ac05_2_plainCopyCancelsPendingClear() {
        guard.copySecret("s3cret");
        guard.copy("rory");

        assertEquals(1, scheduler.cancelled);
        assertFalse(guard.hasPendingSecret());
        guard.clearNow();

        assertEquals(List.of("copy:s3cret:sensitive", "copy:rory:plain"), port.calls);
    }

    @Test
    @DisplayName("AC-05-4：上鎖時立即清除還沒清掉的密碼並取消計時；計時器之後才到也不再清一次")
    void ac05_4_clearNowOnLock() {
        guard.copySecret("s3cret");

        guard.clearNow();

        assertEquals(List.of("copy:s3cret:sensitive", "clear:s3cret"), port.calls);
        assertEquals(1, scheduler.cancelled);
        scheduler.fireAll();
        assertEquals(2, port.calls.size());
    }

    @Test
    @DisplayName("AC-05-4：沒有複製過密碼、或已經清掉時，上鎖不動剪貼簿（使用者自己複製的東西不受影響）")
    void ac05_4_clearNowWithoutPendingDoesNothing() {
        guard.clearNow();
        guard.copySecret("s3cret");
        scheduler.fireAll();
        guard.clearNow();

        assertEquals(List.of("copy:s3cret:sensitive", "clear:s3cret"), port.calls);
    }

    @Test
    @DisplayName("C-6：平台清除暫時失敗（剪貼簿被別的程式佔用）時 1 秒後重試，成功就不再留著密碼")
    void clearFailureRetries() {
        guard.copySecret("s3cret");
        port.failuresLeft = 1;

        scheduler.fireAll();

        assertTrue(guard.hasPendingSecret(), "重試前還要留著密碼才能比對");
        assertEquals(ClipboardGuard.RETRY_DELAY, scheduler.delays.get(scheduler.delays.size() - 1));
        scheduler.fireAll();
        assertEquals(List.of("copy:s3cret:sensitive", "clear:s3cret"), port.calls);
        assertFalse(guard.hasPendingSecret());
    }

    @Test
    @DisplayName("C-6：一直失敗時最多試 3 次就放棄、丟掉密碼，最後一次的例外照常傳出讓平台記錄")
    void clearFailureGivesUp() {
        guard.copySecret("s3cret");
        port.failuresLeft = Integer.MAX_VALUE;

        scheduler.fireAll();
        scheduler.fireAll();
        assertThrows(IllegalStateException.class, scheduler::fireAll);

        assertFalse(guard.hasPendingSecret());
        assertEquals(1 + ClipboardGuard.MAX_CLEAR_ATTEMPTS - 1, scheduler.delays.size(), "只排了 2 次重試");
        scheduler.fireAll();
        assertEquals(ClipboardGuard.MAX_CLEAR_ATTEMPTS, port.clearAttempts);
    }

    @Test
    @DisplayName("C-6：等重試時上鎖立即再試；等重試時複製了別的內容就不再清")
    void retryYieldsToLockAndNewCopy() {
        guard.copySecret("s3cret");
        port.failuresLeft = 1;
        scheduler.fireAll();

        guard.clearNow();
        assertEquals(List.of("copy:s3cret:sensitive", "clear:s3cret"), port.calls);

        guard.copySecret("second");
        port.failuresLeft = 1;
        scheduler.fireAll();
        guard.copy("rory");
        scheduler.fireAll();
        assertEquals(List.of("copy:s3cret:sensitive", "clear:s3cret", "copy:second:sensitive", "copy:rory:plain"),
                port.calls);
    }

    private static final class FakePort implements ClipboardPort {
        final List<String> calls = new ArrayList<>();
        int failuresLeft;
        int clearAttempts;

        @Override
        public void copy(String value, boolean sensitive) {
            calls.add("copy:" + value + (sensitive ? ":sensitive" : ":plain"));
        }

        @Override
        public void clearSecret(String secret) {
            clearAttempts++;
            if (failuresLeft > 0) {
                failuresLeft--;
                throw new IllegalStateException("剪貼簿被別的程式佔用");
            }
            calls.add("clear:" + secret);
        }
    }

    private static final class FakeScheduler implements ClipboardGuard.Scheduler {
        final List<Duration> delays = new ArrayList<>();
        final List<Runnable> tasks = new ArrayList<>();
        int cancelled;

        @Override
        public Runnable schedule(Duration delay, Runnable task) {
            delays.add(delay);
            tasks.add(task);
            return () -> {
                cancelled++;
                tasks.remove(task);
            };
        }

        void fireAll() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            now.forEach(Runnable::run);
        }
    }
}
