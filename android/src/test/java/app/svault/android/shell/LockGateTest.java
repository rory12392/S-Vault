package app.svault.android.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.core.session.VaultSession;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * 上鎖狀態與通知：基底 Activity 依此導回解鎖畫面。
 */
public class LockGateTest {

    private final List<String> events = new ArrayList<>();

    @Test
    public void startsLocked() {
        assertTrue(new LockGate().isLocked());
    }

    @Test
    public void lockNotifiesListenersOnce() {
        LockGate gate = new LockGate();
        gate.addListener(() -> events.add("a"));
        gate.addListener(() -> events.add("b"));
        gate.markUnlocked();
        assertFalse(gate.isLocked());
        gate.lock();
        gate.lock();
        assertTrue(gate.isLocked());
        assertEquals(List.of("a", "b"), events);
    }

    @Test
    public void removedListenerIsNotNotified() {
        LockGate gate = new LockGate();
        LockGate.Listener listener = () -> events.add("x");
        gate.addListener(listener);
        gate.removeListener(listener);
        gate.markUnlocked();
        gate.lock();
        assertEquals(List.of(), events);
    }

    @Test
    public void listenerMayRemoveItselfWhileNotified() {
        LockGate gate = new LockGate();
        LockGate.Listener[] self = new LockGate.Listener[1];
        self[0] = () -> {
            events.add("self");
            gate.removeListener(self[0]);
        };
        gate.addListener(self[0]);
        gate.addListener(() -> events.add("other"));
        gate.markUnlocked();
        gate.lock();
        assertEquals(List.of("self", "other"), events);
    }

    @Test
    public void followsSessionThroughUiExecutor() {
        LockGate gate = new LockGate();
        gate.addListener(() -> events.add("locked"));
        List<Runnable> uiQueue = new ArrayList<>();
        VaultSession.Listener bridge = gate.sessionListener(uiQueue::add);

        // VaultSession 在背景執行緒通知；LockGate 只在 UI 執行緒改變狀態
        bridge.onUnlocked();
        assertTrue("還沒在 UI 執行緒執行前不改變", gate.isLocked());
        uiQueue.remove(0).run();
        assertFalse(gate.isLocked());

        bridge.onLocked();
        assertFalse(gate.isLocked());
        uiQueue.remove(0).run();
        assertTrue(gate.isLocked());
        assertEquals(List.of("locked"), events);
    }
}
