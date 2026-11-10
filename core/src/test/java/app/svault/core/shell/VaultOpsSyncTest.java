package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.sync.SyncReport;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 條目畫面的同步（T-14）：解鎖後與回到前景的更新（AC-07-5）、離線後的「尚未同步，稍後自動重試」、衝突狀態。
 * 兩台裝置共用一個遠端（{@link MemoryLocation}），只用假資料。
 */
class VaultOpsSyncTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T06:00:00Z"), ZoneOffset.UTC);
    private MemoryLocation remote;
    private VaultSession pc;
    private VaultSession phone;
    private VaultOps ops;
    private VaultOps phoneOps;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pc = session("pc");
        phone = session("phone");
        ops = new VaultOps(pc, clock);
        phoneOps = new VaultOps(phone, clock);
    }

    @AfterEach
    void tearDown() {
        pc.lock();
        phone.lock();
    }

    @Test
    @DisplayName("AC-07-5：另一台改過 → refresh 合併並回傳新內容與「合併 n 筆」；沒變時不重畫（data 為 null）")
    void ac07_5_refreshReportsMergedCount() throws Exception {
        phoneOps.addEntry(phone.vault().id(), draft("手機新增"));

        VaultOps.Outcome out = ops.refresh();

        assertTrue(out.merged());
        assertEquals(1, out.sync().changedEntries());
        assertEquals(1, out.data().query().all().size());
        VaultOps.Outcome again = ops.refresh();
        assertNull(again.data());
        assertEquals(SyncReport.Status.UP_TO_DATE, again.sync().status());
    }

    @Test
    @DisplayName("回到前景的檢查至少間隔 30 秒（存檔也算一次）")
    void foregroundRefreshIsThrottled() {
        assertTrue(ops.refreshDue());
        ops.refresh();
        assertFalse(ops.refreshDue());
        clock.advance(Duration.ofSeconds(29));
        assertFalse(ops.refreshDue());
        clock.advance(Duration.ofSeconds(1));
        assertTrue(ops.refreshDue());
        ops.addEntry(pc.vault().id(), draft("電腦新增"));
        assertFalse(ops.refreshDue());
    }

    @Test
    @DisplayName("離線存檔失敗 → 狀態「尚未同步」；恢復連線後的自動重試寫出去 → 已同步")
    void offlineThenAutomaticRetry() {
        remote.offline(true);
        VaultOps.Outcome failed = ops.addEntry(pc.vault().id(), draft("離線新增"));
        assertFalse(failed.saved());
        assertNotNull(failed.saveError());
        assertEquals(VaultOps.SyncState.PENDING, ops.syncState());
        VaultOps.Outcome stillOffline = ops.refresh();
        assertNotNull(stillOffline.saveError());
        assertEquals(VaultOps.SyncState.PENDING, ops.syncState());

        remote.offline(false);
        VaultOps.Outcome retried = ops.refresh();

        assertNull(retried.saveError());
        assertEquals(SyncReport.Status.SAVED, retried.sync().status());
        assertEquals(VaultOps.SyncState.SYNCED, ops.syncState());
        assertFalse(ops.hasUnsavedChanges());
    }

    @Test
    @DisplayName("AC-08-3：有衝突時存檔沒有寫出（saved=false），狀態「有 n 筆衝突」")
    void conflictState() {
        UUID id = ops.addEntry(pc.vault().id(), draft("共用")).subject();
        phoneOps.refresh();
        phoneOps.updateEntry(id, new EntryDraft("共用", "rory", "phone", "", ""), phone.vault().id(),
                phone.vault().id());

        VaultOps.Outcome out = ops.updateEntry(id, new EntryDraft("共用", "rory", "pc", "", ""), pc.vault().id(),
                pc.vault().id());

        assertFalse(out.saved());
        assertNull(out.saveError());
        assertTrue(out.sync().hasConflicts());
        assertEquals(VaultOps.SyncState.CONFLICT, ops.syncState());
        assertEquals(1, ops.conflictCount());
    }

    @Test
    @DisplayName("AC-12-3：上鎖前有沒寫出的修改 → 先存一次（遠端有變更時同樣先合併）")
    void saveBeforeLockMerges() throws Exception {
        remote.offline(true);
        ops.addEntry(pc.vault().id(), draft("離線新增"));
        remote.offline(false);
        phoneOps.addEntry(phone.vault().id(), draft("手機新增"));

        ops.saveBeforeLock();

        assertFalse(ops.hasUnsavedChanges());
        assertEquals(2, ScreenData.of(pc.vault()).query().all().size());
    }

    private VaultSession session(String name) throws Exception {
        VaultSession s = TestSessions.assemble(codec, LocalStore.open(tmp.resolve(name), k -> null, k -> null), clock)
                .session();
        s.unlock(remote, TestVaults.password());
        return s;
    }

    private EntryDraft draft(String title) {
        clock.advance(Duration.ofMinutes(1));
        return new EntryDraft(title, "rory", "pw-" + title, "", "");
    }
}
