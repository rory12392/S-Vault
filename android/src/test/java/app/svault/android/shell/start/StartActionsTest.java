package app.svault.android.shell.start;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.svault.android.platform.FakeDocuments;
import app.svault.android.platform.OpenFailure;
import app.svault.android.platform.SafVaultLocation;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.Hashes;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.WrongPasswordException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 歡迎、建立、解鎖畫面背後的流程（AC-01-1、AC-01-3、AC-06-2、AC-06-3、AC-06-4），用假的文件提供者。
 */
public class StartActionsTest {

    private static final String NEW_DOC = "content://drive/document/new-T08";
    private static final String EXISTING = "content://drive/document/existing-T08";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T02:00:00Z"), ZoneOffset.UTC);
    private final FakeDocuments docs = new FakeDocuments();
    private LocalStore store;
    private RecentStore recent;
    private VaultSession session;
    private StartActions actions;

    @Before
    public void setUp() throws Exception {
        store = LocalStore.open(tmp.getRoot().toPath(), key -> null, key -> null);
        TestSessions.Parts parts = TestSessions.assemble(codec, store, clock);
        recent = parts.recent();
        session = parts.session();
        actions = new StartActions(codec, parts.saver(), session, recent, new BackupStore(store.backupsDir(), clock),
                docs);
    }

    @After
    public void tearDown() {
        session.lock();
    }

    private void existingVault(String uri, String name) throws Exception {
        docs.pick(uri, name + ".kdbx", TestVaults.newVaultBytes(name));
    }

    @Test
    public void ac01_1_createWritesToNewDocumentAndStaysUnlocked() throws Exception {
        // ACTION_CREATE_DOCUMENT 先建立一個 0 位元組的文件，再把 Uri 交給 App
        docs.pick(NEW_DOC, "我的密碼.kdbx", new byte[0]);

        StartActions.CreateOutcome outcome = actions.create("我的密碼", NEW_DOC, TestVaults.password());

        assertEquals(StartActions.CreateOutcome.CREATED, outcome);
        assertTrue("建立後直接是解鎖狀態（不需再輸入一次）", session.isUnlocked());
        assertEquals("我的密碼", session.vault().name());
        assertTrue("取得長期權限，重新啟動後不需重選（AC-06-2）", docs.isPersisted(NEW_DOC));
        byte[] written = docs.content(NEW_DOC);
        try (Vault reopened = codec.open(written, TestVaults.password())) {
            assertEquals(session.vault().id(), reopened.id());
        }
        RecentVault r = recent.find(NEW_DOC).orElseThrow(AssertionError::new);
        assertEquals(Hashes.sha256(written), r.remoteHash());
        assertEquals("我的密碼.kdbx", r.displayName());
        // 空文件沒有東西可以備份：不留一份 0 位元組的「備份」（之後會被當成可以還原的版本）
        assertTrue(new BackupStore(store.backupsDir(), clock).list(session.vault().id()).isEmpty());
    }

    @Test
    public void ac01_1_createRefusesDocumentThatAlreadyHasContent() throws Exception {
        existingVault(EXISTING, "舊的");
        byte[] before = docs.content(EXISTING);

        StartActions.CreateOutcome outcome = actions.create("新的", EXISTING, TestVaults.password());

        assertEquals(StartActions.CreateOutcome.NOT_EMPTY, outcome);
        assertArrayEquals("不覆寫既有的檔案（D-52）", before, docs.content(EXISTING));
        assertFalse(session.isUnlocked());
    }

    @Test
    public void ac01_1_failedFirstWriteRemovesEmptyDocument() throws Exception {
        docs.pick(NEW_DOC, "我的密碼.kdbx", new byte[0]);
        docs.failWriteAfter = 10;

        try {
            actions.create("我的密碼", NEW_DOC, TestVaults.password());
            fail("寫入中斷應該丟例外");
        } catch (VaultIoException expected) {
            assertEquals(VaultIoException.Reason.IO, expected.reason());
        }

        assertFalse(session.isUnlocked());
        assertNull("寫到一半的檔案被刪掉，不留在 Drive 上", docs.content(NEW_DOC));
        assertFalse(docs.isPersisted(NEW_DOC));
        assertTrue(recent.list().isEmpty());
    }

    @Test
    public void ac06_3_recentNewestFirstAndForgetReleasesPermission() throws Exception {
        existingVault(EXISTING, "家用");
        existingVault(NEW_DOC, "工作");
        actions.unlock(actions.pick(EXISTING), TestVaults.password());
        actions.unlock(actions.pick(NEW_DOC), TestVaults.password());

        List<String> names = actions.recent().stream().map(RecentVault::displayName).collect(Collectors.toList());
        assertEquals(List.of("工作.kdbx", "家用.kdbx"), names);

        actions.forget(actions.recent().get(1));

        assertEquals(1, actions.recent().size());
        assertFalse("移除時放棄長期權限（系統對每個 App 的數量有上限）", docs.isPersisted(EXISTING));
        assertNotNull("不刪檔", docs.content(EXISTING));
    }

    @Test
    public void ac06_2_reopenFromRecentAfterRestartWithoutPicking() throws Exception {
        existingVault(EXISTING, "家用");
        actions.unlock(actions.pick(EXISTING), TestVaults.password());
        session.lock();
        docs.restartApp();

        SafVaultLocation again = actions.location(actions.recent().get(0));
        assertNull(actions.check(again));
        actions.unlock(again, TestVaults.password());

        assertTrue(session.isUnlocked());
        assertSame(again, session.location());
    }

    @Test
    public void ac01_3_wrongPasswordStaysLockedAndFileUntouched() throws Exception {
        existingVault(EXISTING, "家用");
        byte[] before = docs.content(EXISTING);
        SafVaultLocation where = actions.pick(EXISTING);

        try {
            actions.unlock(where, "wrong".getBytes(StandardCharsets.UTF_8));
            fail("應該是主密碼錯誤");
        } catch (WrongPasswordException e) {
            assertEquals(OpenFailure.Kind.WRONG_PASSWORD, actions.failure(e, where).kind());
        }
        assertFalse(session.isUnlocked());
        assertArrayEquals(before, docs.content(EXISTING));
    }

    @Test
    public void ac06_4_checkReportsDeletedAndRevokedBeforeAskingForPassword() throws Exception {
        existingVault(EXISTING, "家用");
        SafVaultLocation where = actions.pick(EXISTING);

        docs.delete(EXISTING);
        OpenFailure gone = actions.check(where);
        assertEquals(OpenFailure.Kind.NOT_FOUND, gone.kind());
        assertEquals(OpenFailure.Action.RESELECT_FILE, gone.action());

        existingVault(EXISTING, "家用");
        where = actions.pick(EXISTING);
        docs.revoke(EXISTING);
        assertEquals(OpenFailure.Kind.ACCESS_DENIED, actions.check(where).kind());
    }

    @Test
    public void titleIsFileNameWithoutExtension() {
        assertEquals("我的密碼", StartActions.title("我的密碼.kdbx"));
        assertEquals("家用", StartActions.title("家用.KDBX"));
        assertEquals("備份.kdbx.old", StartActions.title("備份.kdbx.old"));
        assertEquals(".kdbx", StartActions.title(".kdbx"));
    }

    @Test
    public void ac06_4_offlineIsReportedWhenUnlocking() throws Exception {
        existingVault(EXISTING, "家用");
        SafVaultLocation where = actions.pick(EXISTING);
        docs.offline = true;

        // 離線時仍查得到名稱：事先檢查看不出來，解鎖讀內容時才知道
        assertNull(actions.check(where));
        try {
            actions.unlock(where, TestVaults.password());
            fail("離線應該讀不到");
        } catch (VaultIoException e) {
            OpenFailure f = actions.failure(e, where);
            assertEquals(OpenFailure.Kind.UNAVAILABLE, f.kind());
            assertEquals(OpenFailure.Action.RESELECT_FILE, f.action());
        }
    }
}
