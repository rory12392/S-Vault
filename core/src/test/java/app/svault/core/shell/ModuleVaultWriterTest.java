package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryBatchResult.Status;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 模組寫入條目的入口（T-33，支撐 AC-14-1～AC-14-4 的寫入部分）：整批新增到新群組、存檔一次；存檔失敗、合併有衝突、
 * 已有衝突時一筆都不加入（記憶體、{@code pending/} 與遠端都維持原狀）；上鎖時不可用；只開放給匯入模組。
 * 兩台裝置共用一個遠端（{@link MemoryLocation}），只用假資料。
 */
class ModuleVaultWriterTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);
    private final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    private final ModuleVaultWriter writer = new ModuleVaultWriter();
    private final List<EntryBatchResult> results = new ArrayList<>();
    private final List<VaultOps.Outcome> saved = new ArrayList<>();
    private MemoryLocation remote;
    private TestSessions.Parts pcParts;
    private VaultSession pc;
    private VaultSession phone;
    private ModuleVaultAccess.Lease lease;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pcParts = TestSessions.assemble(codec, LocalStore.open(tmp.resolve("pc"), k -> null, k -> null), clock);
        pc = pcParts.session();
        pc.unlock(remote, TestVaults.password());
        phone = TestSessions.assemble(codec, LocalStore.open(tmp.resolve("phone"), k -> null, k -> null), clock)
                .session();
        phone.unlock(remote, TestVaults.password());
        lease = access.open();
        writer.attach(lease, new ModuleVaultWriter.Target(pc, Runnable::run, clock, saved::add));
    }

    @AfterEach
    void tearDown() {
        pc.lock();
        phone.lock();
    }

    @Test
    @DisplayName("T-33：整批加進根群組底下新建的群組、只寫一次遠端；結果帶新群組與筆數，外殼收到新內容")
    void savesWholeBatchInNewGroupWithOneWrite() throws Exception {
        int writes = remote.writes();

        add(batch("Chrome 匯入", 3));

        EntryBatchResult r = only();
        assertEquals(Status.SAVED, r.status());
        assertEquals(3, r.added());
        assertFalse(r.merged());
        assertEquals(writes + 1, remote.writes());
        assertFalse(pc.hasUnsavedChanges());
        try (Vault onDisk = codec.open(remote.content(), TestVaults.password())) {
            GroupView group = onDisk.groups().stream().filter(g -> g.uuid().equals(r.groupId())).findFirst()
                    .orElseThrow();
            assertEquals("Chrome 匯入", group.name());
            assertEquals(onDisk.id(), group.parentId());
            assertEquals(List.of("條目 1", "條目 2", "條目 3"), titlesIn(onDisk, r.groupId()));
        }
        assertEquals(1, saved.size());
        assertEquals(r.groupId(), saved.get(0).subject());
        assertEquals(3, saved.get(0).data().query().all().size());
        assertTrue(saved.get(0).saved());
    }

    @Test
    @DisplayName("T-33：已有同名群組時照樣新建一個")
    void sameNameCreatesAnotherGroup() {
        add(batch("Chrome 匯入", 1));
        add(batch("Chrome 匯入", 1));

        assertEquals(2, results.size());
        assertTrue(results.get(1).saved());
        assertFalse(results.get(0).groupId().equals(results.get(1).groupId()));
        assertEquals(2, pc.vault().groups().stream().filter(g -> g.name().equals("Chrome 匯入")).count());
    }

    @Test
    @DisplayName("T-33：存檔失敗（離線）→ FAILED，一筆都沒有加入：記憶體、pending/、遠端與同步狀態都維持原狀")
    void saveFailureLeavesNoHalfBatch() throws Exception {
        byte[] before = remote.content();
        remote.offline(true);

        add(batch("Chrome 匯入", 5));

        assertEquals(Status.FAILED, only().status());
        assertNull(only().groupId());
        assertEquals(0, only().added());
        assertEquals(0, pc.vault().entries().size());
        assertEquals(1, pc.vault().groups().stream().filter(g -> !g.recycleBin()).count());
        assertFalse(pc.hasUnsavedChanges());
        assertNull(pcParts.saver().readPending(pc.vault().id(), remote));
        assertTrue(saved.isEmpty());
        // 恢復連線後的自動重試也不會把這一批寫出去
        remote.offline(false);
        new VaultOps(pc, clock).refresh();
        assertEquals(0, pc.vault().entries().size());
        assertTrue(Arrays.equals(before, remote.content()));
    }

    @Test
    @DisplayName("T-33：寫回或讀回驗證失敗（failWrite）→ FAILED，遠端與記憶體都沒變")
    void writeFailureLeavesNothing() {
        remote.failWrite(true);
        int writes = remote.writes();

        add(batch("Chrome 匯入", 2));

        assertEquals(Status.FAILED, only().status());
        assertEquals(writes, remote.writes());
        assertEquals(0, pc.vault().entries().size());
        assertNull(pcParts.saver().readPending(pc.vault().id(), remote));
    }

    @Test
    @DisplayName("T-33：之前存檔失敗留下的修改還在 pending/ 時，失敗的這一批不會混進 pending/；成功時一起寫出")
    void earlierPendingIsKeptOnFailureAndWrittenOnSuccess() throws Exception {
        VaultOps ops = new VaultOps(pc, clock);
        remote.offline(true);
        ops.addEntry(pc.vault().id(), draft("離線新增"));
        assertTrue(pc.hasUnsavedChanges());

        add(batch("Chrome 匯入", 4));

        assertEquals(Status.FAILED, results.get(0).status());
        assertTrue(pc.hasUnsavedChanges());
        assertEquals(List.of("離線新增"), titles(pc.vault()));
        byte[] pending = pcParts.saver().readPending(pc.vault().id(), remote);
        try (Vault kept = codec.openWithKeyOf(pending, pc.vault())) {
            assertEquals(List.of("離線新增"), titles(kept));
        }

        remote.offline(false);
        add(batch("Chrome 匯入", 2));

        assertEquals(Status.SAVED, results.get(1).status());
        assertFalse(pc.hasUnsavedChanges());
        assertNull(pcParts.saver().readPending(pc.vault().id(), remote));
        try (Vault onDisk = codec.open(remote.content(), TestVaults.password())) {
            assertEquals(3, onDisk.entries().size());
        }
    }

    @Test
    @DisplayName("T-33：遠端被另一台改過 → 先三方合併再寫，整批與另一台的修改都在；merged=true")
    void mergesOtherDeviceChanges() throws Exception {
        new VaultOps(phone, clock).addEntry(phone.vault().id(), draft("手機新增"));

        add(batch("Chrome 匯入", 2));

        EntryBatchResult r = only();
        assertEquals(Status.SAVED, r.status());
        assertTrue(r.merged());
        try (Vault onDisk = codec.open(remote.content(), TestVaults.password())) {
            assertEquals(3, onDisk.entries().size());
            assertEquals(2, titlesIn(onDisk, r.groupId()).size());
        }
        assertEquals(3, ScreenData.of(pc.vault()).query().all().size());
        assertTrue(saved.get(0).merged());
    }

    @Test
    @DisplayName("T-33：存檔時合併遇到衝突 → CONFLICT，一筆都沒有加入，這台原本的修改與 pending/ 不變、不記成衝突狀態")
    void conflictWhileSavingLeavesNothing() throws Exception {
        VaultOps ops = new VaultOps(pc, clock);
        VaultOps phoneOps = new VaultOps(phone, clock);
        UUID id = ops.addEntry(pc.vault().id(), draft("共用")).subject();
        phoneOps.refresh();
        remote.offline(true);
        ops.updateEntry(id, new EntryDraft("共用", "rory", "pc", "", ""), pc.vault().id(), pc.vault().id());
        remote.offline(false);
        phoneOps.updateEntry(id, new EntryDraft("共用", "rory", "phone", "", ""), phone.vault().id(),
                phone.vault().id());
        byte[] remoteBefore = remote.content();

        add(batch("Chrome 匯入", 3));

        assertEquals(Status.CONFLICT, only().status());
        assertEquals(List.of("共用"), titles(pc.vault()));
        assertEquals("pc", pc.vault().entries().get(0).password());
        assertTrue(pc.hasUnsavedChanges());
        assertTrue(pc.conflict().isEmpty());
        assertTrue(Arrays.equals(remoteBefore, remote.content()));
        try (Vault kept = codec.openWithKeyOf(pcParts.saver().readPending(pc.vault().id(), remote), pc.vault())) {
            assertEquals(List.of("共用"), titles(kept));
        }
        assertTrue(saved.isEmpty());
    }

    @Test
    @DisplayName("T-33：已有還沒解決的衝突 → CONFLICT，不讀寫遠端（要先在衝突對照畫面處理）")
    void existingConflictIsRejected() {
        VaultOps ops = new VaultOps(pc, clock);
        VaultOps phoneOps = new VaultOps(phone, clock);
        UUID id = ops.addEntry(pc.vault().id(), draft("共用")).subject();
        phoneOps.refresh();
        phoneOps.updateEntry(id, new EntryDraft("共用", "rory", "phone", "", ""), phone.vault().id(),
                phone.vault().id());
        ops.updateEntry(id, new EntryDraft("共用", "rory", "pc", "", ""), pc.vault().id(), pc.vault().id());
        assertTrue(pc.conflict().isPresent());
        int writes = remote.writes();

        add(batch("Chrome 匯入", 1));

        assertEquals(Status.CONFLICT, only().status());
        assertEquals(writes, remote.writes());
        assertEquals(1, pc.vault().entries().size());
    }

    @Test
    @DisplayName("T-33：上鎖時不可用——還沒接上、按下上鎖（租約作廢）、session 已上鎖、再解鎖前的舊租約，一律 LOCKED")
    void lockedIsRejected() throws Exception {
        ModuleVaultWriter detached = new ModuleVaultWriter();
        detached.addEntries("importer", batch("A", 1), Runnable::run, results::add);
        access.revoke();
        add(batch("B", 1));
        writer.attach(access.open(), new ModuleVaultWriter.Target(pc, Runnable::run, clock, saved::add));
        pc.lock();
        add(batch("C", 1));
        pc.unlock(remote, TestVaults.password());
        writer.attach(lease, new ModuleVaultWriter.Target(pc, Runnable::run, clock, saved::add));
        add(batch("D", 1));

        assertEquals(List.of(Status.LOCKED, Status.LOCKED, Status.LOCKED, Status.LOCKED),
                results.stream().map(EntryBatchResult::status).toList());
        assertEquals(0, pc.vault().entries().size());
        assertTrue(saved.isEmpty());
    }

    @Test
    @DisplayName("T-33：排進保管庫執行緒後才按下上鎖 → 執行時看到租約作廢，LOCKED；已在執行的那一批照常完成")
    void lockAfterQueueingIsSeenOnTheVaultThread() throws Exception {
        ExecutorService vaultThread = Executors.newSingleThreadExecutor();
        try {
            writer.attach(lease, new ModuleVaultWriter.Target(pc, vaultThread, clock, saved::add));
            CountDownLatch gate = new CountDownLatch(1);
            vaultThread.execute(() -> await(gate));
            writer.addEntries("importer", batch("排隊中", 2), Runnable::run, this::record);
            access.revoke();
            gate.countDown();
            vaultThread.shutdown();
            assertTrue(vaultThread.awaitTermination(30, TimeUnit.SECONDS));
        } finally {
            vaultThread.shutdownNow();
        }
        assertEquals(Status.LOCKED, only().status());
        assertEquals(0, pc.vault().entries().size());
    }

    @Test
    @DisplayName("T-33（D-162）：只開放給匯入模組；其他代號丟 SecurityException，不排進保管庫執行緒")
    void onlyImporterMayWrite() {
        for (String other : new String[] {"generator", "health", "biometric", "autofill", "", null}) {
            assertThrows(SecurityException.class,
                    () -> writer.addEntries(other, batch("X", 1), Runnable::run, results::add));
        }
        assertTrue(results.isEmpty());
        assertEquals(0, pc.vault().entries().size());
        assertEquals(Set.of("importer"), ModuleVaultWriter.ALLOWED_MODULES);
    }

    @Test
    @DisplayName("T-33：結果交給模組指定的 executor、只通知一次；模組的回呼丟例外不會傳出來")
    void callbackRunsOnGivenExecutorAndFailuresStayInside() {
        List<Runnable> queued = new ArrayList<>();
        writer.addEntries("importer", batch("A", 1), queued::add, r -> {
            results.add(r);
            throw new IllegalStateException("模組的錯誤");
        });
        assertTrue(results.isEmpty());
        assertEquals(1, queued.size());
        queued.get(0).run();
        assertEquals(1, results.size());
        assertTrue(results.get(0).saved());
    }

    @Test
    @DisplayName("T-33：外殼的重畫丟例外 → 仍回報 SAVED（已寫進檔案）")
    void redrawFailureStillReportsSaved() {
        writer.attach(lease, new ModuleVaultWriter.Target(pc, Runnable::run, clock, out -> {
            throw new IllegalStateException("畫面已關閉");
        }));

        add(batch("A", 2));

        assertEquals(Status.SAVED, only().status());
        assertEquals(2, pc.vault().entries().size());
    }

    // ---------- 輔助 ----------

    private void add(EntryBatch batch) {
        writer.addEntries("importer", batch, Runnable::run, this::record);
    }

    private synchronized void record(EntryBatchResult r) {
        results.add(r);
    }

    private EntryBatchResult only() {
        assertEquals(1, results.size());
        assertNotNull(results.get(0));
        return results.get(0);
    }

    private EntryBatch batch(String group, int n) {
        List<EntryDraft> drafts = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            drafts.add(new EntryDraft("條目 " + i, "user" + i, "probe1234", "https://example" + i + ".com", ""));
        }
        clock.advance(Duration.ofMinutes(1));
        return new EntryBatch(group, drafts);
    }

    private EntryDraft draft(String title) {
        clock.advance(Duration.ofMinutes(1));
        return new EntryDraft(title, "rory", "pw-" + title, "", "");
    }

    private static List<String> titles(Vault vault) {
        return vault.entries().stream().map(EntryView::title).sorted().toList();
    }

    private static List<String> titlesIn(Vault vault, UUID group) {
        return vault.entries().stream().filter(e -> e.groupId().equals(group)).map(EntryView::title).sorted()
                .toList();
    }

    private static void await(CountDownLatch gate) {
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
