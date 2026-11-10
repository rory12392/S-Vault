package app.svault.android.shell.entries;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import app.svault.android.platform.FakeDocuments;
import app.svault.android.platform.SafVaultLocation;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 條目畫面的寫入操作（US-02）：每個操作都經 {@code VaultSession.save()}（SaveCoordinator）寫進 SAF 文件，
 * 重新開檔看得到。用假的文件提供者與程式建立的保管庫（假資料，密碼 probe1234）。
 */
public class VaultOpsTest {

    private static final String URI = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dt11";
    private static final Instant START = Instant.parse("2026-10-19T04:00:00Z");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final MutableClock clock = new MutableClock(START);
    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final FakeDocuments docs = new FakeDocuments();
    private VaultSession session;
    private VaultOps ops;

    @Before
    public void setUp() throws Exception {
        LocalStore store = LocalStore.open(tmp.getRoot().toPath(), key -> null, key -> null);
        session = TestSessions.assemble(codec, store, clock).session();
        docs.pick(URI, "測試.kdbx", TestVaults.newVaultBytes("測試"));
        session.unlock(SafVaultLocation.adopt(docs, URI), TestVaults.password());
        ops = new VaultOps(session, clock);
    }

    @After
    public void tearDown() {
        session.lock();
    }

    @Test
    public void ac02_1_addedEntryAppearsInItsGroupAndSurvivesReopen() throws Exception {
        UUID root = ops.load().root().uuid();

        VaultOps.Outcome out = ops.addEntry(root, draft("GitHub", "rory", "s3cret", "https://github.com", "備註"));

        assertTrue(out.saved());
        assertEquals(List.of("GitHub"), titles(Listing.of(out.data(), Listing.Scope.group(root), "")));
        try (Vault reopened = reopen()) {
            EntryView e = reopened.entries().get(0);
            assertEquals(out.subject(), e.uuid());
            assertEquals("rory", e.username());
            assertEquals("s3cret", e.password());
            assertEquals("https://github.com", e.url());
            assertEquals("備註", e.notes());
        }
    }

    @Test
    public void ac02_2_updateRefreshesModifiedTimeAndKeepsHistory() throws Exception {
        UUID root = ops.load().root().uuid();
        UUID id = ops.addEntry(root, draft("Mail", "a", "old", "", "")).subject();
        clock.advance(Duration.ofMinutes(5));

        VaultOps.Outcome out = ops.updateEntry(id, draft("Mail", "a", "new", "", ""), root, root);

        EntryView e = out.data().entry(id).orElseThrow();
        assertEquals(START.plus(Duration.ofMinutes(5)), e.lastModified());
        assertEquals(1, e.historyCount());
        try (Vault reopened = reopen()) {
            assertEquals("new", reopened.entries().get(0).password());
            assertEquals(1, reopened.entries().get(0).historyCount());
        }
    }

    @Test
    public void ac02_2_unchangedUpdateWritesNothing() {
        UUID root = ops.load().root().uuid();
        UUID id = ops.addEntry(root, draft("Mail", "a", "pw", "", "")).subject();
        clock.advance(Duration.ofMinutes(5));

        VaultOps.Outcome out = ops.updateEntry(id, draft("Mail", "a", "pw", "", ""), root, root);

        assertEquals(0, out.data().entry(id).orElseThrow().historyCount());
        assertEquals(START, out.data().entry(id).orElseThrow().lastModified());
    }

    @Test
    public void ac02_3_deleteMovesToBinRestoreBringsBackPermanentDeleteRemoves() throws Exception {
        UUID root = ops.load().root().uuid();
        UUID work = ops.addGroup(root, "工作").subject();
        UUID id = ops.addEntry(work, draft("VPN", "", "", "", "")).subject();

        VaultOps.Outcome recycled = ops.recycleEntry(id);
        assertTrue(recycled.recycled());
        assertEquals(List.of("VPN"), titles(Listing.of(recycled.data(), Listing.Scope.bin(), "")));
        assertTrue(Listing.of(recycled.data(), Listing.Scope.group(work), "").entries().isEmpty());

        VaultOps.Outcome restored = ops.restoreEntry(id);
        assertEquals(work, restored.data().entry(id).orElseThrow().groupId());

        ops.recycleEntry(id);
        VaultOps.Outcome deleted = ops.deleteEntryPermanently(id);
        assertFalse(deleted.data().entry(id).isPresent());
        try (Vault reopened = reopen()) {
            assertTrue(reopened.entries().isEmpty());
            assertTrue(reopened.deletedObjects().stream().anyMatch(d -> d.uuid().equals(id)));
        }
    }

    @Test
    public void ac02_4_groupsAddRenameMoveDeleteWithCountAndRestore() throws Exception {
        UUID root = ops.load().root().uuid();
        UUID g = ops.addGroup(root, "  家庭  ").subject();
        assertEquals("家人", ops.renameGroup(g, "家人").data().group(g).orElseThrow().name());

        UUID a = ops.addEntry(root, draft("A", "", "", "", "")).subject();
        ops.addEntry(g, draft("B", "", "", "", ""));
        UUID sub = ops.addGroup(g, "子群組").subject();
        ops.addEntry(sub, draft("C", "", "", "", ""));
        VaultOps.Outcome moved = ops.updateEntry(a, draft("A", "", "", "", ""), root, g);
        assertEquals(g, moved.data().entry(a).orElseThrow().groupId());
        // 刪除確認對話框的條目數（含子群組）
        assertEquals(3, moved.data().query().countInGroup(g));

        VaultOps.Outcome recycled = ops.recycleGroup(g);
        assertTrue(recycled.recycled());
        assertTrue(recycled.data().inRecycleBin(g));
        assertEquals(3, Listing.of(recycled.data(), Listing.Scope.bin(), "").entries().size());

        VaultOps.Outcome restored = ops.restoreGroup(g);
        assertEquals(root, restored.data().group(g).orElseThrow().parentId());
        try (Vault reopened = reopen()) {
            assertTrue(reopened.groups().stream().anyMatch(x -> x.name().equals("家人")));
            assertEquals(4, reopened.groups().size());
        }
    }

    @Test
    public void invalidRequestsThrowAndLeaveVaultUnchanged() {
        UUID root = ops.load().root().uuid();
        assertThrows(IllegalArgumentException.class, () -> ops.addEntry(root, draft(" ", "", "", "", "")));
        assertThrows(IllegalArgumentException.class, () -> ops.recycleEntry(UUID.randomUUID()));
        assertTrue(ops.load().vaultEmpty());
        assertFalse(ops.hasUnsavedChanges());
    }

    @Test
    public void saveFailureKeepsChangesInMemoryUntilRetry() throws Exception {
        UUID root = ops.load().root().uuid();
        docs.offline = true;

        VaultOps.Outcome failed = ops.addEntry(root, draft("離線新增", "", "", "", ""));

        assertNotNull(failed.saveError());
        assertTrue(ops.hasUnsavedChanges());
        assertEquals(List.of("離線新增"), titles(Listing.of(failed.data(), Listing.Scope.all(), "")));
        try (Vault reopened = reopen()) {
            assertTrue("離線時遠端維持原狀", reopened.entries().isEmpty());
        }

        docs.offline = false;
        VaultOps.Outcome retried = ops.retrySave();

        assertNull(retried.saveError());
        assertFalse(ops.hasUnsavedChanges());
        try (Vault reopened = reopen()) {
            assertEquals("離線新增", reopened.entries().get(0).title());
        }
    }

    @Test
    public void ac12_3_saveUnsavedChangesBeforeLock() throws Exception {
        UUID root = ops.load().root().uuid();
        docs.offline = true;
        ops.addEntry(root, draft("上鎖前", "", "", "", ""));
        docs.offline = false;

        ops.saveBeforeLock();
        session.lock();

        assertFalse(ops.hasUnsavedChanges());
        try (Vault reopened = reopen()) {
            assertEquals("上鎖前", reopened.entries().get(0).title());
        }
    }

    private Vault reopen() throws Exception {
        return codec.open(docs.content(URI), TestVaults.password());
    }

    private static EntryDraft draft(String title, String user, String password, String url, String notes) {
        return new EntryDraft(title, user, password, url, notes);
    }

    private static List<String> titles(Listing listing) {
        return listing.entries().stream().map(EntryView::title).collect(Collectors.toList());
    }
}
