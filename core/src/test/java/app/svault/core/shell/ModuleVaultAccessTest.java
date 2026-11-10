package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模組的唯讀保管庫查詢（T-31，Architecture §6；支撐 AC-10-2、AC-10-3）：上鎖與載入中為空、一次解鎖一份、上鎖後已取得的
 * 物件回傳空的結果、等待載入的通知、多執行緒同時查詢與上鎖。
 */
class ModuleVaultAccessTest {

    private static final Instant T = Instant.parse("2026-10-22T00:00:00Z");
    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID WEB = UUID.randomUUID();
    private static final UUID BIN = UUID.randomUUID();

    private final AtomicInteger loads = new AtomicInteger();
    private final ModuleVaultAccess access = new ModuleVaultAccess(loads::incrementAndGet);

    private static EntryView entry(UUID group, String title, String url) {
        return new EntryView(UUID.randomUUID(), group, title, "user", "probe1234", url, "", Map.of(), List.of(), "",
                T, T, 0);
    }

    private static ScreenData data(EntryView... entries) {
        return ScreenData.of(List.of(entries), List.of(
                new GroupView(ROOT, null, "根", "", false, T),
                new GroupView(WEB, ROOT, "網站", "", false, T),
                new GroupView(BIN, ROOT, "資源回收桶", "", true, T)));
    }

    @Test
    @DisplayName("還沒解鎖（含 App 行程被系統重啟後）：為空，不請外殼載入，whenLoaded 回傳 false")
    void lockedIsEmpty() {
        assertTrue(access.unlockedEntries().isEmpty());
        assertFalse(access.whenLoaded(Runnable::run, () -> { throw new AssertionError("不該通知"); }));
        assertEquals(0, loads.get());
    }

    @Test
    @DisplayName("解鎖了但內容還在載入：為空，並請外殼開始載入")
    void loadingIsEmptyAndRequestsLoad() {
        access.open();
        assertTrue(access.unlockedEntries().isEmpty());
        assertEquals(1, loads.get());
    }

    @Test
    @DisplayName("AC-10-3 支撐：解鎖並載入後，查得到自動填入的候選、搜尋、單筆與群組路徑")
    void unlockedQueries() {
        EntryView github = entry(WEB, "GitHub", "https://github.com/login");
        EntryView discord = entry(WEB, "Discord", "androidapp://com.discord");
        EntryView trashed = entry(BIN, "舊的", "https://github.com");
        access.open().publish(data(github, discord, trashed));

        UnlockedEntries q = access.unlockedEntries().orElseThrow();
        assertTrue(q.isOpen());
        assertEquals(List.of(github), q.autofill("com.android.chrome", "github.com"));
        assertEquals(List.of(discord), q.autofill("com.discord", null));
        assertEquals(List.of(discord, github), q.all());
        assertEquals(List.of(discord), q.search("disc"));
        assertEquals(Optional.of(trashed), q.entry(trashed.uuid()));
        assertTrue(q.entry(UUID.randomUUID()).isEmpty());
        assertTrue(q.entry(null).isEmpty());
        assertEquals("根 / 網站", q.groupPath(WEB));
        assertEquals("", q.groupPath(UUID.randomUUID()));
        assertEquals("", q.groupPath(null));
        // 拿到的清單不能改（唯讀）
        assertThrows(UnsupportedOperationException.class, () -> q.all().add(github));
        assertEquals("UnlockedEntries[open=true]", q.toString());
    }

    @Test
    @DisplayName("編輯、存檔後發布的新快照：同一個物件查到的是新的內容")
    void followsNewSnapshots() {
        EntryView a = entry(WEB, "A", "https://a.example");
        EntryView b = entry(WEB, "B", "https://b.example");
        ModuleVaultAccess.Lease lease = access.open();
        lease.publish(data(a));
        UnlockedEntries q = access.unlockedEntries().orElseThrow();
        lease.publish(data(a, b));
        assertEquals(List.of(a, b), q.all());
        lease.publish(null);
        assertEquals(List.of(a, b), q.all());
    }

    @Test
    @DisplayName("上鎖後，模組已經拿到的物件：isOpen 為 false，所有查詢回傳空的結果，不丟例外")
    void revokedHandleIsEmpty() {
        EntryView github = entry(WEB, "GitHub", "https://github.com");
        ModuleVaultAccess.Lease lease = access.open();
        lease.publish(data(github));
        UnlockedEntries q = access.unlockedEntries().orElseThrow();

        access.revoke();
        assertFalse(q.isOpen());
        assertFalse(lease.active());
        assertEquals(List.of(), q.all());
        assertEquals(List.of(), q.search("git"));
        assertEquals(List.of(), q.autofill("com.android.chrome", "github.com"));
        assertTrue(q.entry(github.uuid()).isEmpty());
        assertEquals("", q.groupPath(WEB));
        assertTrue(access.unlockedEntries().isEmpty());
        assertEquals("UnlockedEntries[open=false]", q.toString());
        // 上鎖後背景存檔完成才送來的快照不收
        lease.publish(data(github));
        assertFalse(q.isOpen());
        assertTrue(access.unlockedEntries().isEmpty());
        // 重複上鎖不出錯
        access.revoke();
    }

    @Test
    @DisplayName("再解鎖（或還原後換內容）：舊的物件不會恢復，新取得的才查得到")
    void reopenDoesNotReviveOldHandle() {
        EntryView a = entry(WEB, "A", "https://a.example");
        access.open().publish(data(a));
        UnlockedEntries old = access.unlockedEntries().orElseThrow();

        ModuleVaultAccess.Lease second = access.open();
        assertFalse(old.isOpen());
        assertTrue(access.unlockedEntries().isEmpty());
        second.publish(data(a));
        assertFalse(old.isOpen());
        assertEquals(List.of(), old.all());
        UnlockedEntries fresh = access.unlockedEntries().orElseThrow();
        assertEquals(List.of(a), fresh.all());
    }

    @Test
    @DisplayName("whenLoaded：等到第一份內容才通知一次；已載入時立即通知")
    void whenLoadedNotifiesOnce() {
        List<String> calls = new ArrayList<>();
        ModuleVaultAccess.Lease lease = access.open();
        assertTrue(access.whenLoaded(Runnable::run, () -> calls.add("等待中")));
        assertEquals(1, loads.get());
        assertEquals(List.of(), calls);

        lease.publish(data(entry(WEB, "A", "https://a.example")));
        assertEquals(List.of("等待中"), calls);
        lease.publish(data());
        assertEquals(List.of("等待中"), calls);

        assertTrue(access.whenLoaded(Runnable::run, () -> calls.add("已載入")));
        assertEquals(List.of("等待中", "已載入"), calls);
    }

    @Test
    @DisplayName("whenLoaded：等待中上鎖就不通知，之後再解鎖也不會補通知")
    void whenLoadedDroppedOnLock() {
        AtomicBoolean called = new AtomicBoolean();
        access.open();
        assertTrue(access.whenLoaded(Runnable::run, () -> called.set(true)));
        access.revoke();
        access.open().publish(data());
        assertFalse(called.get());
    }

    @Test
    @DisplayName("whenLoaded：等待中還原備份（換一份租約、沒有上鎖）時，等新的內容載入後通知")
    void whenLoadedCarriesOverOnReopen() {
        AtomicInteger called = new AtomicInteger();
        ModuleVaultAccess.Lease first = access.open();
        assertTrue(access.whenLoaded(Runnable::run, called::incrementAndGet));
        ModuleVaultAccess.Lease second = access.open();
        first.publish(data());
        assertEquals(0, called.get());
        second.publish(data());
        assertEquals(1, called.get());
    }

    @Test
    @DisplayName("故障注入：模組的通知丟例外、executor 拒絕時，發布內容的外殼執行緒照常")
    void callbackFailuresStayInside() {
        ModuleVaultAccess.Lease lease = access.open();
        AtomicBoolean third = new AtomicBoolean();
        access.whenLoaded(Runnable::run, () -> { throw new IllegalStateException("模組出錯"); });
        access.whenLoaded(r -> { throw new RejectedExecutionException(); }, () -> { });
        access.whenLoaded(Runnable::run, () -> third.set(true));
        lease.publish(data());
        assertTrue(third.get());
        assertTrue(access.unlockedEntries().isPresent());
    }

    @Test
    @DisplayName("模組拿到的介面只有查詢：沒有寫入、存檔、上鎖或取得金鑰的方法，實作類別也不公開")
    void readOnlySurface() {
        Set<String> allowed = Set.of("isOpen", "all", "search", "autofill", "entry", "groupPath");
        for (Method m : UnlockedEntries.class.getMethods()) {
            assertTrue(allowed.contains(m.getName()), "多了方法：" + m.getName());
        }
        access.open().publish(data());
        UnlockedEntries q = access.unlockedEntries().orElseThrow();
        // 實作是私有類別：模組無法轉型回租約去發布內容
        assertTrue(java.lang.reflect.Modifier.isPrivate(q.getClass().getModifiers()));
        assertSame(q, access.unlockedEntries().orElseThrow());
    }

    @Test
    @DisplayName("多執行緒：查詢的同時反覆解鎖、發布、上鎖，不丟例外；最後上鎖後全部為空")
    void concurrentQueriesAndLock() throws Exception {
        EntryView github = entry(WEB, "GitHub", "https://github.com");
        ScreenData snapshot = data(github);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        List<UnlockedEntries> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        try {
            List<Future<?>> readers = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                readers.add(pool.submit(() -> {
                    start.await();
                    while (!stop.get()) {
                        access.unlockedEntries().ifPresent(q -> {
                            List<EntryView> found = q.autofill("com.android.chrome", "github.com");
                            // 作廢的瞬間可能是空的，但不會是別的內容
                            assertTrue(found.isEmpty() || found.equals(List.of(github)));
                            if (seen.size() < 50) {
                                seen.add(q);
                            }
                        });
                    }
                    return null;
                }));
            }
            start.countDown();
            for (int i = 0; i < 2_000; i++) {
                access.open().publish(snapshot);
                access.revoke();
            }
            stop.set(true);
            for (Future<?> r : readers) {
                r.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(access.unlockedEntries().isEmpty());
        for (UnlockedEntries q : seen) {
            assertFalse(q.isOpen());
            assertEquals(List.of(), q.autofill("com.android.chrome", "github.com"));
        }
    }
}
