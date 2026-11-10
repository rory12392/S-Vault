package app.svault.core.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.BackupStore;
import app.svault.core.storage.CorruptBackupException;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.storage.RestoreResult;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 解鎖中還原（AC-09-2）：開啟中的保管庫換成還原後的版本，不經過上鎖（畫面直接重新載入）。
 */
class VaultSessionRestoreTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T02:00:00Z"), ZoneOffset.UTC);
    private final List<String> events = new ArrayList<>();
    private SaveCoordinator saver;
    private VaultSession session;
    private MemoryLocation location;

    @BeforeEach
    void setUp() throws Exception {
        LocalStore store = LocalStore.open(tmp, key -> null, key -> null);
        TestSessions.Parts parts = TestSessions.assemble(codec, store, clock);
        saver = parts.saver();
        session = parts.session();
        location = new MemoryLocation("G:/雲端/家用.kdbx", TestVaults.newVaultBytes("家用"));
        session.unlock(location, TestVaults.password());
        new VaultEditor(session.vault(), clock).addEntry(session.vault().id(), new EntryDraft("一", "", "", "", ""));
        session.save();
        clock.advanceSeconds(1);
        session.addListener(new VaultSession.Listener() {
            @Override
            public void onUnlocked() {
                events.add("unlocked");
            }

            @Override
            public void onLocked() {
                events.add("locked");
            }
        });
    }

    @Test
    @DisplayName("AC-09-2：還原後開啟中的保管庫換成備份的內容，舊的清零，不發上鎖通知")
    void replacesOpenVaultWithoutLocking() throws Exception {
        Vault before = session.vault();
        BackupStore.Backup chosen = saver.listBackups(before.id()).get(0);

        RestoreResult result = session.restore(chosen);

        assertTrue(session.isUnlocked());
        assertSame(result.vault(), session.vault());
        assertNotSame(before, session.vault());
        assertTrue(before.isClosed(), "舊的保管庫清零");
        assertEquals(0, session.vault().entries().size(), "備份是新增「一」之前的版本");
        assertSame(location, session.location());
        try (Vault backup = codec.openWithKeyOf(Files.readAllBytes(chosen.file()), session.vault());
             Vault remote = codec.openWithKeyOf(location.content(), session.vault())) {
            assertEquals(TestVaults.content(backup), TestVaults.content(remote), "遠端的內容是選定的備份");
        }
        assertTrue(events.isEmpty(), "沒有上鎖／解鎖通知：畫面直接重新載入");
    }

    @Test
    @DisplayName("故障注入：還原失敗時維持原本開啟中的保管庫")
    void failureKeepsCurrentVault() throws Exception {
        Vault before = session.vault();
        BackupStore.Backup chosen = saver.listBackups(before.id()).get(0);
        Files.write(chosen.file(), new byte[] {1, 2, 3});

        assertThrows(CorruptBackupException.class, () -> session.restore(chosen));

        assertSame(before, session.vault());
        assertFalse(before.isClosed());
        assertEquals(1, session.vault().entries().size());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("上鎖中不能用這個方法還原（改用 SaveCoordinator.restoreWithPassword＋adopt）")
    void lockedSessionRejects() throws Exception {
        BackupStore.Backup chosen = saver.listBackups(session.vault().id()).get(0);
        session.lock();
        assertThrows(IllegalStateException.class, () -> session.restore(chosen));
    }
}
