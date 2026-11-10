package app.svault.android.shell.entries;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

/** {@link EntriesSlot}：一次解鎖一份，上鎖通知和主執行緒同時進來不會互等。 */
public class EntriesSlotTest {

    private final AtomicBoolean unlocked = new AtomicBoolean(true);
    private final List<Object> disposed = new ArrayList<>();
    private final EntriesSlot<Object> slot = new EntriesSlot<>(unlocked::get, Object::new, disposed::add);

    @Test
    public void createsOncePerUnlockAndDropsOnLock() {
        Object first = slot.get();
        assertNotNull(first);
        assertSame(first, slot.get());
        assertSame(first, slot.peek());

        unlocked.set(false);
        slot.drop();
        assertEquals(List.of(first), disposed);
        assertNull(slot.get());
        assertNull(slot.peek());
    }

    @Test
    public void peekDoesNotCreate() {
        assertNull(slot.peek());
    }

    /** 重現 T-31 的情況：通知端拿著 session 的鎖要 {@code drop}，主執行緒要 {@code get}（要問 session）。 */
    @Test(timeout = 10_000)
    public void lockNotificationAndGetDoNotWaitForEachOther() throws InterruptedException {
        Object sessionLock = new Object();
        CountDownLatch asking = new CountDownLatch(1);
        CountDownLatch notifying = new CountDownLatch(1);
        EntriesSlot<Object> shared = new EntriesSlot<>(() -> {
            asking.countDown();
            await(notifying);
            synchronized (sessionLock) {
                return unlocked.get();
            }
        }, Object::new, disposed::add);

        Thread main = new Thread(shared::get, "main");
        main.start();
        asking.await();
        Thread locker = new Thread(() -> {
            synchronized (sessionLock) {
                unlocked.set(false);
                notifying.countDown();
                shared.drop();
            }
        }, "locker");
        locker.start();
        locker.join();
        main.join();
        assertNull(shared.peek());
    }

    /** 問過「解鎖中」之後才上鎖、通知也已經過了：剛建立的狀態不能留下來給下一次解鎖。 */
    @Test
    public void lockBetweenCheckAndCreateLeavesNothing() {
        AtomicBoolean firstAsk = new AtomicBoolean(true);
        EntriesSlot<Object> racing = new EntriesSlot<>(() -> firstAsk.getAndSet(false), Object::new, disposed::add);

        assertNull(racing.get());
        assertNull(racing.peek());
        assertEquals(1, disposed.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
