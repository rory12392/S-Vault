package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.storage.RemoteChangedException;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import app.svault.core.vault.WrongPasswordException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 合併接入解鎖與存檔流程（T-14；Architecture §5）：兩台裝置（PC、手機）各自的本機資料夾，共用一個遠端檔案。
 */
class SyncServiceTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T02:00:00Z"), ZoneOffset.UTC);
    private MemoryLocation remote;
    private SyncDevice pc;
    private SyncDevice phone;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/我的雲端硬碟/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pc = new SyncDevice(tmp.resolve("pc"), codec, remote, clock);
        phone = new SyncDevice(tmp.resolve("phone"), codec, remote, clock);
        pc.unlock();
        phone.unlock();
    }

    @AfterEach
    void tearDown() {
        pc.session.lock();
        phone.session.lock();
    }

    @Test
    @DisplayName("AC-07-1：存檔前遠端被另一台改過 → 先讀入合併再寫入，兩邊的新增都在")
    void ac07_1_saveMergesRemoteChangesFirst() throws Exception {
        phone.add("手機新增", "p1");

        SyncReport report = pc.editAndSave(e -> e.addEntry(pc.session.vault().id(),
                new EntryDraft("電腦新增", "rory", "c1", "", "")));

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertEquals(1, report.changedEntries());
        assertEquals(List.of("手機新增", "電腦新增"), pc.titles());
        assertEquals(List.of("手機新增", "電腦新增"), titlesOf(remote.content()));
        assertFalse(pc.session.hasUnsavedChanges());
    }

    @Test
    @DisplayName("C-2：讀完遠端、寫回之前另一台剛好寫入 → 不蓋掉，重新合併後再寫，兩邊的新增都在")
    void c2_concurrentWriteIsMergedNotOverwritten() throws Exception {
        byte[] before = remote.content();
        phone.add("手機新增", "p1");
        byte[] phoneVersion = remote.content();
        remote.replace(before);
        remote.replaceAfterNextRead(phoneVersion);

        SyncReport report = pc.editAndSave(e -> e.addEntry(pc.session.vault().id(),
                new EntryDraft("電腦新增", "rory", "c1", "", "")));

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertEquals(List.of("手機新增", "電腦新增"), titlesOf(remote.content()));
        assertEquals(List.of("手機新增", "電腦新增"), pc.titles());
    }

    @Test
    @DisplayName("C-2（Codex M-04 審查待辦 4）：重試 3 次都撞到別人寫入 → 放棄、遠端不被蓋掉、這台的修改留在 pending/；之後再存檔就寫進去")
    void c2_givesUpAfterThreeConflictsAndKeepsPending() throws Exception {
        phone.add("手機 1", "p1");
        byte[] a = remote.content();
        phone.add("手機 2", "p2");
        byte[] b = remote.content();
        remote.changeAfterEveryRead(a, b);
        int writes = remote.writes();

        assertThrows(RemoteChangedException.class, () -> pc.editAndSave(e -> e.addEntry(pc.session.vault().id(),
                new EntryDraft("電腦新增", "rory", "c1", "", ""))));

        remote.changeAfterEveryRead(null, null);
        assertEquals(writes, remote.writes(), "三次都撞到時一次都沒寫遠端");
        assertTrue(pc.session.hasUnsavedChanges());
        assertTrue(Files.exists(pc.pendingFile(pc.session.vault().id())), "這台的修改留在 pending/");
        assertTrue(pc.titles().contains("電腦新增"));

        SyncReport retry = pc.session.save();
        assertEquals(SyncReport.Status.MERGED, retry.status());
        assertTrue(titlesOf(remote.content()).contains("電腦新增"));
        assertTrue(titlesOf(remote.content()).contains("手機 1"));
    }

    @Test
    @DisplayName("AC-07-1：遠端沒有被改過 → 直接寫入，不合併")
    void ac07_1_unchangedRemoteIsJustWritten() throws Exception {
        UUID id = pc.add("只有電腦", "c1");

        assertEquals(List.of("只有電腦"), titlesOf(remote.content()));
        SyncReport again = pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("只有電腦", "rory", "c2", "", "")));

        assertEquals(SyncReport.Status.SAVED, again.status());
        assertEquals(0, again.changedEntries());
    }

    @Test
    @DisplayName("AC-07-5：解鎖後讀到另一台的修改 → 合併 n 筆、內容換成最新；這台沒有修改時不寫遠端")
    void ac07_5_refreshAfterUnlockMergesWithoutWriting() throws Exception {
        pc.session.lock();
        UUID shared = phone.add("共用", "old");
        phone.add("手機新增", "p1");
        phone.editAndSave(e -> e.updateEntry(shared, new EntryDraft("共用", "rory", "new", "", "")));
        int writes = remote.writes();

        pc.unlock();
        SyncReport report = pc.session.refresh();

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertEquals(2, report.changedEntries());
        assertEquals(List.of("共用", "手機新增"), pc.titles());
        assertEquals("new", pc.entry("共用").password());
        assertEquals(writes, remote.writes(), "這台沒有修改：只更新本機的基準，不寫遠端");
        assertArrayEquals(remote.content(), pc.base());
    }

    @Test
    @DisplayName("AC-07-5：回到前景時另一台改過 → 合併；都沒變時什麼都不做")
    void ac07_5_foregroundRefresh() throws Exception {
        assertEquals(SyncReport.Status.UP_TO_DATE, pc.session.refresh().status());

        phone.add("手機新增", "p1");
        SyncReport report = pc.session.refresh();

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertEquals(1, report.changedEntries());
        assertEquals(List.of("手機新增"), pc.titles());
        assertEquals(SyncReport.Status.UP_TO_DATE, pc.session.refresh().status());
    }

    @Test
    @DisplayName("AC-07-8：基準是本機加密保存的上次同步版本（同一主密碼），和遠端寫入的位元組相同")
    void ac07_8_baseIsEncryptedLastSyncedRemote() throws Exception {
        pc.add("電腦新增", "c1");

        byte[] base = pc.base();
        assertArrayEquals(remote.content(), base);
        assertFalse(new String(base, StandardCharsets.ISO_8859_1).contains("電腦新增"));
        assertThrows(WrongPasswordException.class, () -> codec.open(base, "wrong".getBytes(StandardCharsets.UTF_8)));
        try (Vault v = codec.open(base, TestVaults.password())) {
            assertEquals(1, v.entries().size());
        }
    }

    @Test
    @DisplayName("AC-07-8：合併以基準判斷誰改了什麼：同一筆兩邊改不同欄位都保留（AC-07-3）")
    void ac07_8_baseDecidesWhoChangedWhat() throws Exception {
        UUID id = pc.add("共用", "old");
        phone.session.refresh();
        phone.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "old", "", "手機的備註")));

        SyncReport report = pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "new", "", "")));

        assertEquals(SyncReport.Status.MERGED, report.status());
        EntryView merged = pc.entry("共用");
        assertEquals("new", merged.password());
        assertEquals("手機的備註", merged.notes());
    }

    @Test
    @DisplayName("AC-07-8：基準遺失時退回「沒有基準」的規則：不遺失資料，通知第一次同步")
    void ac07_8_missingBaseFallsBackToFirstSync() throws Exception {
        pc.add("電腦新增", "c1");
        phone.add("手機新增", "p1");
        Files.delete(pc.baseFile());

        SyncReport report = pc.session.refresh();

        assertEquals(List.of("手機新增", "電腦新增"), pc.titles());
        assertTrue(report.notices().stream().anyMatch(n -> n.kind() == MergeNotice.Kind.FIRST_SYNC));
        assertTrue(Files.exists(pc.baseFile()));
    }

    @Test
    @DisplayName("第一次在這台裝置開啟：遠端就是基準")
    void firstOpenOnDeviceStoresBase() throws Exception {
        SyncDevice tablet = new SyncDevice(tmp.resolve("tablet"), codec, remote, clock);
        tablet.unlock();
        try {
            assertArrayEquals(remote.content(), tablet.base());
            assertEquals(SyncReport.Status.UP_TO_DATE, tablet.session.refresh().status());
        } finally {
            tablet.session.lock();
        }
    }

    @Test
    @DisplayName("離線存檔失敗 → 修改寫進 pending/（加密）；上鎖再解鎖仍在，恢復連線後自動寫出並刪除 pending/")
    void offlineSaveKeepsPendingAndRetries() throws Exception {
        UUID vaultId = pc.session.vault().id();
        remote.offline(true);
        assertThrows(VaultIoException.class, () -> pc.add("離線新增", "o1"));
        assertTrue(Files.exists(pc.pendingFile(vaultId)));
        assertTrue(pc.session.hasUnsavedChanges());
        pc.session.lock();

        remote.offline(false);
        pc.unlock();
        assertEquals(List.of("離線新增"), pc.titles());
        assertTrue(pc.session.hasUnsavedChanges());
        SyncReport report = pc.session.refresh();

        assertEquals(SyncReport.Status.SAVED, report.status());
        assertEquals(List.of("離線新增"), titlesOf(remote.content()));
        assertFalse(Files.exists(pc.pendingFile(vaultId)));
        assertFalse(pc.session.hasUnsavedChanges());
    }

    @Test
    @DisplayName("離線時另一台也改了 → 恢復後重試會合併兩邊（AC-07-2）")
    void offlineEditMergedAfterReconnect() throws Exception {
        remote.offline(true);
        assertThrows(VaultIoException.class, () -> pc.add("離線新增", "o1"));
        remote.offline(false);
        phone.add("手機新增", "p1");

        SyncReport report = pc.session.refresh();

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertEquals(List.of("手機新增", "離線新增"), titlesOf(remote.content()));
    }

    @Test
    @DisplayName("AC-08-1／AC-08-3：同一欄位兩邊改成不同值 → 停在「有衝突、未套用」：不寫遠端，修改進 pending/")
    void conflictStopsWithoutWriting() throws Exception {
        UUID id = pc.add("共用", "old");
        phone.session.refresh();
        phone.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "phone", "", "")));
        byte[] before = remote.content();

        SyncReport report = pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "pc", "", "")));

        assertEquals(SyncReport.Status.CONFLICT, report.status());
        assertEquals(1, report.conflicts());
        assertArrayEquals(before, remote.content());
        assertTrue(Files.exists(pc.pendingFile(pc.session.vault().id())));
        assertEquals("pc", pc.entry("共用").password(), "畫面仍是這台的版本");
        MergeResult conflict = pc.session.conflict().orElseThrow();
        assertEquals(id, conflict.conflicts().get(0).entryId());
    }

    @Test
    @DisplayName("AC-08-2：衝突解決的入口：選這台的值後寫回，另一台的值進歷史紀錄")
    void resolveWritesChosenSide() throws Exception {
        UUID id = pc.add("共用", "old");
        phone.session.refresh();
        phone.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "phone", "", "")));
        pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "pc", "", "")));
        Conflict c = pc.session.conflict().orElseThrow().conflicts().get(0);

        SyncReport report = pc.session.resolve(Map.of(id, c.choose(Side.LOCAL)));

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertFalse(pc.session.conflict().isPresent());
        try (Vault v = codec.open(remote.content(), TestVaults.password())) {
            assertEquals("pc", v.entries().get(0).password());
            assertTrue(v.history(id).stream().anyMatch(h -> h.password().equals("phone")));
        }
    }

    @Test
    @DisplayName("AC-08-5 的基礎：有衝突時上鎖，下次解鎖讀回 pending/、更新後又回到衝突")
    void conflictSurvivesLock() throws Exception {
        UUID id = pc.add("共用", "old");
        phone.session.refresh();
        phone.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "phone", "", "")));
        pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "pc", "", "")));
        pc.session.lock();

        pc.unlock();
        assertEquals("pc", pc.entry("共用").password());
        SyncReport report = pc.session.refresh();

        assertEquals(SyncReport.Status.CONFLICT, report.status());
    }

    @Test
    @DisplayName("AC-07-4：存檔時發現另一台刪除了這台修改的條目 → 保留修改並通知")
    void deleteVersusEditNotice() throws Exception {
        UUID id = pc.add("共用", "old");
        phone.session.refresh();
        phone.editAndSave(e -> e.recycleEntry(id));

        SyncReport report = pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "new", "", "")));

        assertEquals(SyncReport.Status.MERGED, report.status());
        assertTrue(report.notices().stream().anyMatch(n -> n.kind() == MergeNotice.Kind.KEPT_LOCAL_EDIT));
        assertEquals("new", pc.entry("共用").password());
    }

    @Test
    @DisplayName("解不開的 pending/ 移到一旁（不刪除），照常解鎖")
    void unusablePendingIsSetAside() throws Exception {
        UUID vaultId = pc.session.vault().id();
        pc.session.lock();
        Files.createDirectories(pc.pendingFile(vaultId).getParent());
        Files.write(pc.pendingFile(vaultId), "不是 KDBX".getBytes(StandardCharsets.UTF_8));

        pc.unlock();

        assertFalse(pc.session.hasUnsavedChanges());
        assertFalse(Files.exists(pc.pendingFile(vaultId)));
        Path pending = pc.pendingFile(vaultId);
        assertTrue(Files.exists(pending.resolveSibling(pending.getFileName() + ".unusable")));
    }

    @Test
    @DisplayName("遠端被換成另一個保管庫：不合併、不覆寫，修改留在 pending/")
    void foreignRemoteIsRejected() throws Exception {
        UUID vaultId = pc.session.vault().id();
        byte[] other = TestVaults.newVaultBytes("別的");
        remote.replace(other);

        assertThrows(VaultException.class, () -> pc.add("電腦新增", "c1"));

        assertArrayEquals(other, remote.content());
        assertTrue(Files.exists(pc.pendingFile(vaultId)));
    }

    @Test
    @DisplayName("更新時遠端檔案不見了：回報 NOT_FOUND，不自動重建")
    void refreshDoesNotRecreateMissingRemote() throws Exception {
        MemoryLocation gone = new MemoryLocation("H:/不見了.kdbx", null);
        SyncService service = new SyncService(codec, new app.svault.core.storage.SaveCoordinator(codec, pc.store,
                new app.svault.core.storage.RecentStore(pc.store.recentFile()), clock));

        VaultIoException e = assertThrows(VaultIoException.class,
                () -> service.sync(pc.session.vault(), false, gone, false));

        assertEquals(VaultIoException.Reason.NOT_FOUND, e.reason());
        assertEquals(null, gone.content());
    }

    private List<String> titlesOf(byte[] bytes) throws VaultException {
        try (Vault v = codec.open(bytes, TestVaults.password())) {
            return v.entries().stream().map(EntryView::title).sorted().toList();
        }
    }
}
