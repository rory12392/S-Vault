package app.svault.android.shell.start;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.svault.android.platform.FakeDocuments;
import app.svault.android.platform.OpenFailure;
import app.svault.android.platform.SafVaultLocation;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.LocalStore;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.VaultException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 備份還原畫面背後的流程（AC-09-2、AC-09-5），用假的文件提供者（{@code "wt"} 覆寫＋讀回比對）。
 */
public class RestoreActionsTest {

    private static final String URI = "content://drive/document/restore-T12";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-19T02:00:00Z"), ZoneOffset.UTC);
    private final FakeDocuments docs = new FakeDocuments();
    private VaultSession session;
    private StartActions actions;
    private SafVaultLocation location;

    @Before
    public void setUp() throws Exception {
        LocalStore store = LocalStore.open(tmp.getRoot().toPath(), key -> null, key -> null);
        TestSessions.Parts parts = TestSessions.assemble(codec, store, clock);
        session = parts.session();
        actions = new StartActions(codec, parts.saver(), session, parts.recent(),
                new BackupStore(store.backupsDir(), clock), docs);
        docs.pick(URI, "家用.kdbx", TestVaults.newVaultBytes("家用"));
        location = actions.pick(URI);
        actions.unlock(location, TestVaults.password());
        addAndSave("一");
        addAndSave("二");
    }

    @After
    public void tearDown() {
        session.lock();
    }

    private void addAndSave(String title) throws Exception {
        Vault v = session.vault();
        new VaultEditor(v, clock).addEntry(v.id(), new EntryDraft(title, "", "", "", ""));
        session.save();
    }

    /** 上鎖，再把檔案截成一半（覆寫中斷），解鎖時就是「檔案可能損壞」。 */
    private void corruptAndLock() {
        session.lock();
        byte[] good = docs.content(URI);
        docs.pick(URI, "家用.kdbx", Arrays.copyOf(good, good.length / 2));
    }

    // 還原後的檔案：內容和備份相同；歷史與刪除紀錄可能多了還原的註記（T-27，core 的 RestoreMarks）
    private void assertSameContent(byte[] backup, byte[] restored) throws Exception {
        try (Vault b = codec.open(backup, TestVaults.password()); Vault r = codec.open(restored, TestVaults.password())) {
            assertEquals("檔案的內容是選定的備份", TestVaults.content(b), TestVaults.content(r));
        }
    }

    @Test
    public void ac09_2_restoreWhileUnlockedReplacesOpenVault() throws Exception {
        List<BackupStore.Backup> list = actions.backups();
        assertEquals(2, list.size());

        actions.restore(list.get(0));

        assertTrue(session.isUnlocked());
        assertEquals(1, session.vault().entries().size());
        assertSameContent(Files.readAllBytes(list.get(0).file()), docs.content(URI));
        assertEquals("還原前的版本也備份了", 3, actions.backups().size());
    }

    @Test
    public void ac09_5_corruptFileOffersRestoreAndRestoresWithPassword() throws Exception {
        corruptAndLock();
        try {
            actions.unlock(location, TestVaults.password());
            fail("截斷的檔案應該打不開");
        } catch (CorruptVaultException e) {
            assertEquals(OpenFailure.Action.RESTORE_BACKUP, actions.failure(e, location).action());
        }
        List<BackupStore.Backup> list = actions.backupsOf(URI);
        assertEquals(2, list.size());

        actions.restoreAndUnlock(location, list.get(0), TestVaults.password());

        assertTrue(session.isUnlocked());
        assertEquals(1, session.vault().entries().size());
        assertSameContent(Files.readAllBytes(list.get(0).file()), docs.content(URI));
    }

    @Test
    public void ac09_5_wrongPasswordWritesNothing() throws Exception {
        corruptAndLock();
        byte[] before = docs.content(URI);
        BackupStore.Backup chosen = actions.backupsOf(URI).get(0);
        try {
            actions.restoreAndUnlock(location, chosen, "錯的".getBytes(StandardCharsets.UTF_8));
            fail("主密碼錯誤");
        } catch (VaultException e) {
            OpenFailure f = OpenFailure.ofRestore(e);
            assertEquals(OpenFailure.Kind.WRONG_PASSWORD, f.kind());
            assertEquals(OpenFailure.Action.NONE, f.action());
        }
        assertFalse(session.isUnlocked());
        assertArrayEquals(before, docs.content(URI));
        assertEquals(2, actions.backupsOf(URI).size());
    }

    @Test
    public void faultInjection_corruptBackupIsRejectedAndOffersOtherVersion() throws Exception {
        corruptAndLock();
        byte[] before = docs.content(URI);
        BackupStore.Backup chosen = actions.backupsOf(URI).get(0);
        Files.write(chosen.file(), new byte[] {9, 9, 9});
        try {
            actions.restoreAndUnlock(location, chosen, TestVaults.password());
            fail("壞掉的備份不能還原");
        } catch (VaultException e) {
            OpenFailure f = OpenFailure.ofRestore(e);
            assertEquals(OpenFailure.Kind.BACKUP_UNUSABLE, f.kind());
            assertEquals(OpenFailure.Action.OTHER_BACKUP, f.action());
        }
        assertArrayEquals(before, docs.content(URI));
    }

    @Test
    public void faultInjection_interruptedOverwriteCanBeRestoredAgain() throws Exception {
        BackupStore.Backup chosen = actions.backups().get(1);
        byte[] chosenBytes = Files.readAllBytes(chosen.file());
        docs.failWriteAfter = 100;
        try {
            actions.restore(chosen);
            fail("寫到一半中斷");
        } catch (VaultException e) {
            assertEquals(OpenFailure.Kind.RESTORE_FAILED, OpenFailure.ofRestore(e).kind());
        }
        // SAF 不能原子取代：中斷時檔案被截斷了，但還原前的版本與選定的備份都還在，再還原一次就好（AC-09-5）
        assertEquals(2, session.vault().entries().size());
        List<BackupStore.Backup> list = actions.backups();
        assertEquals(3, list.size());
        docs.failWriteAfter = -1;

        actions.restore(chosen);

        assertSameContent(chosenBytes, docs.content(URI));
        assertEquals(0, session.vault().entries().size());
    }
}
