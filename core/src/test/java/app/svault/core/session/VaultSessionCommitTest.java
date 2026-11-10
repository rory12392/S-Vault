package app.svault.core.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.sync.SyncReport;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link VaultSession#commit}（T-33）：在複本上修改、存檔一次，全部成功才換掉開啟中的保管庫；修改途中丟例外時什麼都不留。
 * 存檔失敗、合併衝突、上鎖與開放範圍由 core {@code ModuleVaultWriterTest} 從模組的入口測。
 */
class VaultSessionCommitTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);
    private MemoryLocation remote;
    private TestSessions.Parts parts;
    private VaultSession session;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        parts = TestSessions.assemble(codec, LocalStore.open(tmp.resolve("pc"), k -> null, k -> null), clock);
        session = parts.session();
        session.unlock(remote, TestVaults.password());
    }

    @AfterEach
    void tearDown() {
        session.lock();
    }

    @Test
    @DisplayName("T-33：成功 → 換成寫出的那一份（舊的上鎖清零）、已同步；開啟中的保管庫在套用時沒有被改")
    void successSwapsInWrittenCopy() throws Exception {
        Vault before = session.vault();
        int[] seenInOriginal = new int[1];

        SyncReport report = session.commit(copy -> {
            assertNotSame(before, copy);
            new VaultEditor(copy, clock).addEntry(copy.id(), new EntryDraft("新條目", "u", "probe1234", "", ""));
            seenInOriginal[0] = before.entries().size();
        });

        assertEquals(SyncReport.Status.SAVED, report.status());
        assertEquals(0, seenInOriginal[0]);
        assertNotSame(before, session.vault());
        assertTrue(before.isClosed());
        assertEquals(1, session.vault().entries().size());
        assertFalse(session.hasUnsavedChanges());
    }

    @Test
    @DisplayName("T-33：修改途中丟例外（加到一半）→ 例外原樣傳出；記憶體、遠端、pending/ 都沒變")
    void failureMidwayLeavesNothing() {
        Vault before = session.vault();
        byte[] remoteBefore = remote.content();
        int writes = remote.writes();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> session.commit(copy -> {
            VaultEditor editor = new VaultEditor(copy, clock);
            editor.addEntry(copy.id(), new EntryDraft("第一筆", "", "", "", ""));
            editor.addEntry(copy.id(), new EntryDraft(" ", "", "", "", ""));
        }));

        assertEquals("名稱不能是空的", e.getMessage());
        assertSame(before, session.vault());
        assertFalse(before.isClosed());
        assertEquals(0, session.vault().entries().size());
        assertEquals(writes, remote.writes());
        assertArrayEquals(remoteBefore, remote.content());
        assertNull(parts.saver().readPending(before.id(), remote));
        assertFalse(session.hasUnsavedChanges());
    }

    @Test
    @DisplayName("T-33：已上鎖時丟 IllegalStateException，不呼叫修改")
    void lockedThrows() {
        session.lock();
        boolean[] called = new boolean[1];

        assertThrows(IllegalStateException.class, () -> session.commit(copy -> called[0] = true));

        assertFalse(called[0]);
    }
}
