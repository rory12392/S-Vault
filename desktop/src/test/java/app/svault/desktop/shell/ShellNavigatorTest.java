package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * desktop 的 {@code EntryNavigator}（T-30；Architecture §6）：解鎖後才接上條目畫面，上鎖後的呼叫不做事，
 * 其他執行緒的呼叫轉到 UI 執行緒。
 */
class ShellNavigatorTest {

    private final AtomicBoolean onUi = new AtomicBoolean(true);
    private final List<Runnable> posted = new ArrayList<>();
    private final ShellNavigator navigator = new ShellNavigator(onUi::get, posted::add);
    private final List<UUID> opened = new ArrayList<>();

    @Test
    @DisplayName("還沒解鎖（沒接上）與上鎖後：不做事、不丟例外")
    void lockedDoesNothing() {
        navigator.openEntry(UUID.randomUUID());
        navigator.attach(opened::add);
        navigator.detach();
        navigator.openEntry(UUID.randomUUID());
        navigator.openEntry(null);
        assertEquals(List.of(), opened);
    }

    @Test
    @DisplayName("解鎖後：交給條目畫面（含 null，由條目畫面顯示「找不到」）")
    void unlockedOpens() {
        UUID id = UUID.randomUUID();
        navigator.attach(opened::add);
        navigator.openEntry(id);
        navigator.openEntry(null);
        assertEquals(java.util.Arrays.asList(id, null), opened);
    }

    @Test
    @DisplayName("其他執行緒的呼叫轉到 UI 執行緒；排進去之後才上鎖時不做事")
    void otherThreadsArePosted() {
        UUID id = UUID.randomUUID();
        navigator.attach(opened::add);
        onUi.set(false);
        navigator.openEntry(id);
        assertEquals(List.of(), opened);
        assertEquals(1, posted.size());

        onUi.set(true);
        posted.get(0).run();
        assertEquals(List.of(id), opened);

        onUi.set(false);
        navigator.openEntry(id);
        onUi.set(true);
        navigator.detach();
        posted.get(1).run();
        assertEquals(List.of(id), opened);
    }
}
