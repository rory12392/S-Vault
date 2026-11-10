package app.svault.android.platform;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Android 的整段流程：選檔 → 解鎖 → 存檔 → 重新啟動，以及覆寫中斷後的損壞偵測（AC-06-2、AC-09-5）。
 * 用 core 的 {@link VaultSession}／{@link SaveCoordinator} 與假的文件提供者。
 */
public class SafSaveFlowTest {

    private static final String URI = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dv1";
    private static final String NAME = "流程測試保管庫-T06";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T02:00:00Z"), ZoneOffset.UTC);
    private final FakeDocuments docs = new FakeDocuments();
    private LocalStore store;
    private RecentStore recent;
    private VaultSession session;

    @Before
    public void setUp() throws Exception {
        store = LocalStore.open(tmp.getRoot().toPath(), key -> null, key -> null);
        recent = new RecentStore(store.recentFile());
        session = newSession();
        docs.pick(URI, "流程.kdbx", TestVaults.newVaultBytes(NAME));
    }

    @After
    public void tearDown() {
        session.lock();
    }

    private VaultSession newSession() {
        // 每次都是新的 session（模擬重新啟動）；recent.json 是同一個檔案，recent 讀得到它寫的內容
        return TestSessions.assemble(codec, store, clock).session();
    }

    private BackupStore backups() {
        return new BackupStore(store.backupsDir(), clock);
    }

    @Test
    public void ac06_2_pickUnlockSaveThenRestartWithoutPicking() throws Exception {
        session.unlock(SafVaultLocation.adopt(docs, URI), TestVaults.password());
        session.save();
        session.lock();

        // 重新啟動：選擇器的暫時權限沒了，從 recent.json 重建位置
        docs.restartApp();
        session = newSession();
        List<RecentVault> list = recent.list();
        assertEquals(1, list.size());
        RecentVault last = list.get(0);
        assertEquals(URI, last.location());
        session.unlock(SafVaultLocation.reopen(docs, last.location(), last.displayName()), TestVaults.password());
        assertEquals(NAME, session.vault().name());
        session.save();
    }

    @Test
    public void ac09_5_interruptedOverwriteIsDetectedAndBackupHasLastGoodVersion() throws Exception {
        session.unlock(SafVaultLocation.adopt(docs, URI), TestVaults.password());
        byte[] lastGood = docs.content(URI);

        docs.failWriteAfter = lastGood.length / 2;
        try {
            session.save();
            fail("覆寫中斷應該回報存檔失敗");
        } catch (VaultIoException e) {
            assertEquals(VaultIoException.Reason.IO, e.reason());
        }
        session.lock();
        docs.failWriteAfter = -1;

        // 下次開啟：偵測到損壞（不是主密碼錯誤），而且提供「從備份還原」
        session = newSession();
        SafVaultLocation again = SafVaultLocation.reopen(docs, URI, "流程.kdbx");
        OpenFailure failure = openFailure(again);
        assertEquals(OpenFailure.Kind.CORRUPT, failure.kind());
        assertTrue(OpenFailure.hasBackups(recent, backups(), URI));
        assertEquals(OpenFailure.Action.RESTORE_BACKUP,
                OpenFailure.of(new app.svault.core.vault.CorruptVaultException("x"),
                        OpenFailure.hasBackups(recent, backups(), URI)).action());

        // 資料最多回到上次成功存檔的狀態：最新的備份就是中斷前的原檔，能用主密碼開
        RecentVault rv = recent.find(URI).orElseThrow();
        List<BackupStore.Backup> list = backups().list(rv.vaultId());
        byte[] newest = Files.readAllBytes(list.get(0).file());
        assertArrayEquals(lastGood, newest);
        try (Vault restored = codec.open(newest, TestVaults.password())) {
            assertEquals(NAME, restored.name());
        }
    }

    @Test
    public void ac09_5_everyTruncationIsCorruptNotWrongPassword() throws Exception {
        SafVaultLocation.adopt(docs, URI);
        byte[] full = docs.content(URI);
        // 標頭附近逐位元組、其餘每段取樣：覆寫中斷可能停在任何地方
        int[] cuts = Stream.concat(
                java.util.stream.IntStream.range(0, Math.min(400, full.length)).boxed(),
                java.util.stream.IntStream.iterate(400, i -> i < full.length - 1, i -> i + 97).boxed())
                .mapToInt(Integer::intValue).toArray();
        for (int cut : cuts) {
            docs.setContent(URI, java.util.Arrays.copyOf(full, cut));
            OpenFailure failure = openFailure(SafVaultLocation.reopen(docs, URI, "流程.kdbx"));
            assertEquals("截斷在 " + cut + " 位元組", OpenFailure.Kind.CORRUPT, failure.kind());
        }
    }

    @Test
    public void noBackupsMeansNoRestoreOffer() throws Exception {
        assertFalse("沒開過的檔案沒有備份", OpenFailure.hasBackups(recent, backups(), URI));
        session.unlock(SafVaultLocation.adopt(docs, URI), TestVaults.password());
        assertFalse("還沒存過檔也沒有備份", OpenFailure.hasBackups(recent, backups(), URI));
    }

    @Test
    public void ac09_4_safSaveLeavesNoPlaintextInPrivateFolder() throws Exception {
        session.unlock(SafVaultLocation.adopt(docs, URI), TestVaults.password());
        session.save();
        byte[] marker = NAME.getBytes(StandardCharsets.UTF_8);
        try (Stream<Path> files = Files.walk(store.root())) {
            for (Path p : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                assertFalse("明文出現在 " + p.getFileName(), contains(readOrEmpty(p), marker));
            }
        }
    }

    private OpenFailure openFailure(SafVaultLocation location) {
        try {
            session.unlock(location, TestVaults.password());
            fail("應該開不了");
            return null;
        } catch (VaultException e) {
            return OpenFailure.of(e, true);
        }
    }

    private static byte[] readOrEmpty(Path p) {
        try {
            return Files.readAllBytes(p);
        } catch (IOException e) {
            return new byte[0];
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
