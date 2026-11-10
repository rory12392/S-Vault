package app.svault.desktop.shell.entries;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MutableClock;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.SaveResult;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.desktop.platform.FileVaultLocation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 三欄畫面的寫入操作（US-02）：每個操作都經 {@code VaultSession.save()} 寫進檔案，重新開檔看得到。
 * 只用暫存資料夾裡以程式建立的保管庫（假資料，密碼 probe1234）。
 */
class VaultOpsTest {

    private static final Instant START = Instant.parse("2026-10-18T04:00:00Z");

    @TempDir
    Path dir;

    private final MutableClock clock = new MutableClock(START);
    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private Path file;
    private VaultSession session;
    private VaultOps ops;

    @BeforeEach
    void setUp() throws Exception {
        LocalStore store = LocalStore.open(dir.resolve("data"), k -> null, k -> null);
        TestSessions.Parts parts = TestSessions.assemble(codec, store, clock);
        SaveCoordinator saver = parts.saver();
        session = parts.session();
        file = dir.resolve("測試.kdbx");
        FileVaultLocation location = new FileVaultLocation(file);
        Vault created = codec.create("測試", TestVaults.password());
        SaveResult r = saver.save(created, location);
        session.adopt(created, location, r.remoteHash());
        ops = new VaultOps(session, clock);
    }

    @Test
    @DisplayName("AC-02-1：新增的條目出現在所屬群組的清單中，重新開啟保管庫後仍存在")
    void addEntryPersists() throws Exception {
        ScreenData data = ops.load();
        UUID root = data.root().uuid();

        VaultOps.Outcome out = ops.addEntry(root, draft("GitHub", "rory", "s3cret", "https://github.com", "備註"));

        assertTrue(out.saved());
        assertEquals(List.of("GitHub"), titles(Listing.of(out.data(), Listing.Scope.group(root), "")));
        try (Vault reopened = codec.open(Files.readAllBytes(file), TestVaults.password())) {
            EntryView e = reopened.entries().get(0);
            assertEquals(out.subject(), e.uuid());
            assertEquals("rory", e.username());
            assertEquals("s3cret", e.password());
            assertEquals("https://github.com", e.url());
            assertEquals("備註", e.notes());
        }
    }

    @Test
    @DisplayName("AC-02-2：編輯後修改時間更新，舊值留在 KDBX 歷史紀錄（寫進檔案）")
    void updateKeepsHistory() throws Exception {
        UUID root = ops.load().root().uuid();
        UUID id = ops.addEntry(root, draft("Mail", "a", "old", "", "")).subject();
        clock.advance(Duration.ofMinutes(5));

        VaultOps.Outcome out = ops.updateEntry(id, draft("Mail", "a", "new", "", ""), root, root);

        EntryView e = out.data().entry(id).orElseThrow();
        assertEquals(START.plus(Duration.ofMinutes(5)), e.lastModified());
        assertEquals(1, e.historyCount());
        try (Vault reopened = codec.open(Files.readAllBytes(file), TestVaults.password())) {
            assertEquals("new", reopened.entries().get(0).password());
            assertEquals(1, reopened.entries().get(0).historyCount());
        }
    }

    @Test
    @DisplayName("AC-02-2：內容與群組都沒變時不產生歷史版本、不改時間")
    void unchangedUpdateDoesNothing() {
        UUID root = ops.load().root().uuid();
        UUID id = ops.addEntry(root, draft("Mail", "a", "pw", "", "")).subject();
        clock.advance(Duration.ofMinutes(5));

        VaultOps.Outcome out = ops.updateEntry(id, draft("Mail", "a", "pw", "", ""), root, root);

        assertEquals(0, out.data().entry(id).orElseThrow().historyCount());
        assertEquals(START, out.data().entry(id).orElseThrow().lastModified());
    }

    @Test
    @DisplayName("AC-02-3：刪除的條目移到資源回收桶，可以還原回原群組；回收桶裡的可以永久刪除")
    void recycleRestoreAndDelete() throws Exception {
        UUID root = ops.load().root().uuid();
        UUID work = ops.addGroup(root, "工作").subject();
        UUID id = ops.addEntry(work, draft("VPN", "", "", "", "")).subject();

        VaultOps.Outcome recycled = ops.recycleEntry(id);
        assertTrue(recycled.recycled());
        UUID bin = recycled.data().recycleBin().orElseThrow().uuid();
        assertEquals(List.of("VPN"), titles(Listing.of(recycled.data(), Listing.Scope.bin(bin), "")));
        assertTrue(Listing.of(recycled.data(), Listing.Scope.group(work), "").entries().isEmpty());

        VaultOps.Outcome restored = ops.restoreEntry(id);
        assertEquals(work, restored.data().entry(id).orElseThrow().groupId());

        ops.recycleEntry(id);
        VaultOps.Outcome deleted = ops.deleteEntryPermanently(id);
        assertTrue(deleted.data().entry(id).isEmpty());
        try (Vault reopened = codec.open(Files.readAllBytes(file), TestVaults.password())) {
            assertTrue(reopened.entries().isEmpty());
            assertTrue(reopened.deletedObjects().stream().anyMatch(d -> d.uuid().equals(id)));
        }
    }

    @Test
    @DisplayName("AC-02-4：新增、重新命名群組，移動條目；刪除群組時條目數正確並一起移到回收桶、可還原")
    void groupOperations() throws Exception {
        UUID root = ops.load().root().uuid();
        UUID g = ops.addGroup(root, "  家庭  ").subject();
        VaultOps.Outcome renamed = ops.renameGroup(g, "家人");
        assertEquals("家人", renamed.data().group(g).orElseThrow().name());

        UUID a = ops.addEntry(root, draft("A", "", "", "", "")).subject();
        ops.addEntry(g, draft("B", "", "", "", ""));
        UUID sub = ops.addGroup(g, "子群組").subject();
        ops.addEntry(sub, draft("C", "", "", "", ""));
        VaultOps.Outcome moved = ops.moveEntry(a, g);
        assertEquals(g, moved.data().entry(a).orElseThrow().groupId());
        assertEquals(3, moved.data().query().countInGroup(g));

        VaultOps.Outcome recycled = ops.recycleGroup(g);
        assertTrue(recycled.recycled());
        assertTrue(recycled.data().inRecycleBin(g));
        UUID bin = recycled.data().recycleBin().orElseThrow().uuid();
        assertEquals(3, Listing.of(recycled.data(), Listing.Scope.bin(bin), "").entries().size());
        assertFalse(recycled.data().activeGroups().stream().anyMatch(x -> x.uuid().equals(g)));

        VaultOps.Outcome restored = ops.restoreGroup(g);
        assertEquals(root, restored.data().group(g).orElseThrow().parentId());
        assertEquals(3, restored.data().query().countInGroup(g));
        try (Vault reopened = codec.open(Files.readAllBytes(file), TestVaults.password())) {
            assertTrue(reopened.groups().stream().anyMatch(x -> x.name().equals("家人")));
        }
    }

    @Test
    @DisplayName("編輯時改群組：內容與群組一起寫進檔案")
    void updateMovesGroup() {
        UUID root = ops.load().root().uuid();
        UUID g = ops.addGroup(root, "工作").subject();
        UUID id = ops.addEntry(root, draft("Jira", "", "", "", "")).subject();

        VaultOps.Outcome out = ops.updateEntry(id, draft("Jira", "me", "", "", ""), root, g);

        assertEquals(g, out.data().entry(id).orElseThrow().groupId());
        assertEquals("me", out.data().entry(id).orElseThrow().username());
    }

    @Test
    @DisplayName("名稱空白或找不到條目時操作失敗，保管庫不變")
    void invalidRequestsThrow() {
        UUID root = ops.load().root().uuid();
        assertThrows(IllegalArgumentException.class, () -> ops.addEntry(root, draft(" ", "", "", "", "")));
        assertThrows(IllegalArgumentException.class, () -> ops.recycleEntry(UUID.randomUUID()));
        assertTrue(ops.load().vaultEmpty());
        assertFalse(ops.hasUnsavedChanges());
    }

    @Test
    @DisplayName("存檔失敗（檔案讀不到：路徑被資料夾佔住）時修改留在記憶體、標記尚未儲存；檔案回來後再試一次就寫進去")
    void saveFailureKeepsChangesUntilRetry() throws Exception {
        UUID root = ops.load().root().uuid();
        byte[] original = Files.readAllBytes(file);
        Files.delete(file);
        Files.createDirectory(file);

        VaultOps.Outcome failed = ops.addEntry(root, draft("離線新增", "", "", "", ""));

        assertNotNull(failed.saveError());
        assertTrue(ops.hasUnsavedChanges());
        assertEquals(List.of("離線新增"), titles(Listing.of(failed.data(), Listing.Scope.group(root), "")));
        assertTrue(Files.isDirectory(file), "讀不到遠端時不覆寫（D-41）");

        Files.delete(file);
        Files.write(file, original);
        VaultOps.Outcome retried = ops.retrySave();

        assertNull(retried.saveError());
        assertFalse(ops.hasUnsavedChanges());
        try (Vault reopened = codec.open(Files.readAllBytes(file), TestVaults.password())) {
            assertEquals("離線新增", reopened.entries().get(0).title());
        }
    }

    @Test
    @DisplayName("AC-12-3：上鎖前把尚未儲存的修改存一次")
    void saveBeforeLock() throws Exception {
        UUID root = ops.load().root().uuid();
        byte[] original = Files.readAllBytes(file);
        Files.delete(file);
        Files.createDirectory(file);
        ops.addEntry(root, draft("上鎖前", "", "", "", ""));
        Files.delete(file);
        Files.write(file, original);

        ops.saveBeforeLock();
        session.lock();

        assertFalse(ops.hasUnsavedChanges());
        try (Vault reopened = codec.open(Files.readAllBytes(file), TestVaults.password())) {
            assertEquals("上鎖前", reopened.entries().get(0).title());
        }
    }

    private static EntryDraft draft(String title, String user, String password, String url, String notes) {
        return new EntryDraft(title, user, password, url, notes);
    }

    private static List<String> titles(Listing listing) {
        return listing.entries().stream().map(EntryView::title).toList();
    }
}
