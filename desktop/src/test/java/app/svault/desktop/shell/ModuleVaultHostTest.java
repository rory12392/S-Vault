package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ScreenData;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultEditor;
import app.svault.desktop.shell.ext.ModuleHost;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * desktop {@link ModuleHost} 的唯讀保管庫（T-32）：規則和 android 的 T-31 相同（core {@code ModuleVaultAccessTest}
 * 已測租約本身），這裡測 desktop 的 host 有接上、每個模組共用同一份、外殼的開關順序（解鎖、還原、按下上鎖）與
 * 預設實作。外殼的呼叫點（{@code StartFlow}、{@code LockFlow}、{@code VaultScreen}）在 JavaFX 上，用開發版的
 * {@code --demo-modules} 畫面腳本驗證（dev-log）。
 */
class ModuleVaultHostTest {

    private static final Instant T = Instant.parse("2026-10-22T00:00:00Z");
    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID WEB = UUID.randomUUID();

    private final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    private final Function<String, ModuleHost> hosts = DesktopModules.hosts(
            LiveSettings.inMemory(Settings.defaults()), Runnable::run, id -> { }, access);

    private static EntryView entry(String title) {
        return new EntryView(UUID.randomUUID(), WEB, title, "user", "probe1234", "https://example.com", "",
                Map.of(), List.of(), "", T, T, 0);
    }

    private static ScreenData data(EntryView... entries) {
        return ScreenData.of(List.of(entries), List.of(
                new GroupView(ROOT, null, "根", "", false, T),
                new GroupView(WEB, ROOT, "網站", "", false, T)));
    }

    @Test
    @DisplayName("T-32：上鎖時為空、解鎖後條目畫面還在載入時也為空；whenEntriesLoaded 在上鎖時回傳 false")
    void lockedAndLoadingAreEmpty() {
        ModuleHost health = hosts.apply("health");
        assertEquals(Optional.empty(), health.unlockedEntries());
        assertFalse(health.whenEntriesLoaded(Runnable::run, () -> { }));

        access.open();
        assertEquals(Optional.empty(), health.unlockedEntries());
    }

    @Test
    @DisplayName("T-32：條目畫面發布快照後，每個模組的 host 都查得到同一份內容，之後的編輯自動換新")
    void unlockedQueriesAreSharedAcrossModules() {
        EntryView a = entry("Alpha");
        ModuleVaultAccess.Lease lease = access.open();
        lease.publish(data(a, entry("Beta")));

        UnlockedEntries health = hosts.apply("health").unlockedEntries().orElseThrow();
        UnlockedEntries importer = hosts.apply("importer").unlockedEntries().orElseThrow();
        assertEquals(2, health.all().size());
        assertEquals(List.of(a), importer.search("alp"));
        assertEquals(Optional.of(a), health.entry(a.uuid()));
        assertEquals("根 / 網站", health.groupPath(WEB));

        lease.publish(data(a));
        assertEquals(1, health.all().size());
        assertEquals(1, importer.all().size());
    }

    @Test
    @DisplayName("T-32：上鎖後已取得的物件不再回傳內容、不丟例外，再解鎖也不恢復；要重新向 host 取")
    void lockRevokesHeldHandle() {
        access.open().publish(data(entry("Alpha")));
        ModuleHost host = hosts.apply("health");
        UnlockedEntries held = host.unlockedEntries().orElseThrow();

        access.revoke();
        assertFalse(held.isOpen());
        assertEquals(List.of(), held.all());
        assertEquals(Optional.empty(), host.unlockedEntries());

        access.open().publish(data(entry("Alpha"), entry("Beta")));
        assertFalse(held.isOpen());
        assertEquals(List.of(), held.search(""));
        UnlockedEntries fresh = host.unlockedEntries().orElseThrow();
        assertNotSame(held, fresh);
        assertEquals(2, fresh.all().size());
    }

    @Test
    @DisplayName("T-32：還原後重新載入（外殼再 open 一次）時，舊物件失效，等待中的通知等新內容到了才來一次")
    void restoreSwapsLeaseAndCarriesWaiters() {
        access.open().publish(data(entry("舊")));
        ModuleHost host = hosts.apply("health");
        UnlockedEntries before = host.unlockedEntries().orElseThrow();

        ModuleVaultAccess.Lease reloaded = access.open();
        assertFalse(before.isOpen());
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger fired = new AtomicInteger();
        assertTrue(host.whenEntriesLoaded(queued::add, fired::incrementAndGet));
        assertEquals(List.of(), queued);

        reloaded.publish(data(entry("新"), entry("新 2")));
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(1, fired.get());
        assertEquals(2, host.unlockedEntries().orElseThrow().all().size());
    }

    @Test
    @DisplayName("T-32：等待載入中按下上鎖，通知不會來；上鎖後才送到的快照（背景存檔完成）不收")
    void lockWhileWaitingDropsCallbackAndLateSnapshots() {
        ModuleVaultAccess.Lease lease = access.open();
        ModuleHost host = hosts.apply("importer");
        List<Runnable> queued = new ArrayList<>();
        assertTrue(host.whenEntriesLoaded(queued::add, () -> { }));

        access.revoke();
        lease.publish(data(entry("上鎖後才到")));
        assertEquals(List.of(), queued);
        assertEquals(Optional.empty(), host.unlockedEntries());
    }

    @Test
    @DisplayName("T-32：模組在背景執行緒查詢、JavaFX 執行緒同時解鎖／換內容／上鎖：不丟例外，只會看到完整內容或空的")
    void backgroundQueriesWhileShellLocks() throws InterruptedException {
        ModuleHost host = hosts.apply("health");
        AtomicBoolean stop = new AtomicBoolean();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        Set<Integer> sizes = ConcurrentHashMap.newKeySet();
        CountDownLatch done = new CountDownLatch(2);
        for (int i = 0; i < 2; i++) {
            Thread t = new Thread(() -> {
                try {
                    while (!stop.get()) {
                        host.unlockedEntries().ifPresent(q -> {
                            sizes.add(q.all().size());
                            q.search("a");
                        });
                    }
                } catch (Throwable e) {
                    errors.add(e);
                } finally {
                    done.countDown();
                }
            });
            t.setDaemon(true);
            t.start();
        }
        ScreenData two = data(entry("Alpha"), entry("Beta"));
        for (int i = 0; i < 2_000; i++) {
            access.open().publish(two);
            access.revoke();
        }
        stop.set(true);
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(List.of(), List.copyOf(errors));
        assertTrue(Set.of(0, 2).containsAll(sizes), "看到不完整的內容：" + sizes);
    }

    @Test
    @DisplayName("T-32：沒接上保管庫的 host（舊的 hosts 多載、測試用的假 host）一律是上鎖")
    void hostsWithoutVaultAreLocked() {
        ModuleHost legacy = DesktopModules.hosts(LiveSettings.inMemory(Settings.defaults()), Runnable::run,
                id -> { }).apply("health");
        assertEquals(Optional.empty(), legacy.unlockedEntries());
        assertFalse(legacy.whenEntriesLoaded(Runnable::run, () -> { }));

        ModuleHost fake = new ModuleHost() {
            @Override
            public ModulePreferences preferences() {
                return null;
            }

            @Override
            public EntryNavigator navigator() {
                return id -> { };
            }
        };
        assertEquals(Optional.empty(), fake.unlockedEntries());
        assertFalse(fake.whenEntriesLoaded(Runnable::run, () -> { }));
    }

    @Test
    @DisplayName("T-32：ModuleHost 不交出 VaultSession、Vault、VaultEditor 與主密碼（byte[]、char[]）")
    void hostHandsOutNoWriteAccessOrSecrets() {
        Set<Class<?>> forbidden = Set.of(VaultSession.class, Vault.class, VaultEditor.class, byte[].class,
                char[].class);
        for (Method m : ModuleHost.class.getMethods()) {
            assertFalse(forbidden.contains(m.getReturnType()), m.getName() + " 回傳 " + m.getReturnType());
        }
    }
}
