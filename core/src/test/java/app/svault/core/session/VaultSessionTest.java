package app.svault.core.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.Hashes;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.RecentVault;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.SaveResult;
import app.svault.core.storage.VaultIoException;
import app.svault.core.sync.SyncReport;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.WrongPasswordException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 解鎖狀態的骨架：解鎖、記住最近開啟（AC-06-3）、存檔、手動上鎖與清零。
 */
class VaultSessionTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T02:00:00Z"), ZoneOffset.UTC);
    private final List<String> events = new ArrayList<>();
    private RecentStore recent;
    private VaultSession session;
    private MemoryLocation location;
    private UUID vaultId;

    @BeforeEach
    void setUp() throws Exception {
        LocalStore store = LocalStore.open(tmp, key -> null, key -> null);
        TestSessions.Parts parts = TestSessions.assemble(codec, store, clock);
        recent = parts.recent();
        session = parts.session();
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
        try (Vault v = codec.create("家用", TestVaults.password())) {
            vaultId = v.id();
            location = new MemoryLocation("G:/雲端/家用.kdbx", codec.serialize(v));
        }
    }

    @Test
    @DisplayName("AC-06-3：解鎖後記住位置、保管庫 ID 與遠端雜湊（不記主密碼）")
    void unlockRemembersLocationAndHash() throws Exception {
        session.unlock(location, TestVaults.password());

        assertTrue(session.isUnlocked());
        assertEquals(vaultId, session.vault().id());
        assertSame(location, session.location());
        RecentVault r = recent.find("G:/雲端/家用.kdbx").orElseThrow();
        assertEquals(vaultId, r.vaultId());
        assertEquals("G:/雲端/家用.kdbx", r.displayName());
        assertEquals(clock.instant(), r.lastOpened());
        assertEquals(Hashes.sha256(location.content()), r.remoteHash());
        assertEquals(List.of("unlocked"), events);
    }

    @Test
    @DisplayName("主密碼錯誤時維持上鎖、不記錄")
    void wrongPasswordStaysLocked() {
        assertThrows(WrongPasswordException.class,
                () -> session.unlock(location, "wrong".getBytes(StandardCharsets.UTF_8)));
        assertFalse(session.isUnlocked());
        assertTrue(recent.list().isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("檔案讀不到時丟 VaultIoException（AC-06-4 的原因），維持上鎖")
    void unreadableLocation() {
        VaultIoException e = assertThrows(VaultIoException.class,
                () -> session.unlock(new MemoryLocation("gone.kdbx", null), TestVaults.password()));
        assertEquals(VaultIoException.Reason.NOT_FOUND, e.reason());
        assertFalse(session.isUnlocked());
    }

    @Test
    @DisplayName("手動上鎖：Vault 清零並丟掉、通知一次；之後不能取得保管庫")
    void lockClearsVault() throws Exception {
        session.unlock(location, TestVaults.password());
        Vault vault = session.vault();

        session.lock();
        session.lock();

        assertTrue(vault.isClosed(), "金鑰材料已清零");
        assertFalse(session.isUnlocked());
        assertThrows(IllegalStateException.class, session::vault);
        assertThrows(IllegalStateException.class, session::location);
        assertThrows(IllegalStateException.class, session::save);
        assertEquals(List.of("unlocked", "locked"), events);
    }

    @Test
    @DisplayName("存檔經 SaveCoordinator 寫回並更新遠端雜湊")
    void saveWritesThroughCoordinator() throws Exception {
        session.unlock(location, TestVaults.password());
        byte[] before = location.content();

        SyncReport result = session.save();

        assertEquals(SyncReport.Status.SAVED, result.status());
        String written = Hashes.sha256(location.content());
        assertNotEquals(Hashes.sha256(before), written);
        assertEquals(written, recent.find(location.persistentId()).orElseThrow().remoteHash());
        try (Vault reopened = codec.open(location.content(), TestVaults.password())) {
            assertEquals(vaultId, reopened.id());
        }
    }

    @Test
    @DisplayName("已解鎖時開另一個保管庫：先上鎖舊的")
    void unlockAnotherLocksPrevious() throws Exception {
        session.unlock(location, TestVaults.password());
        Vault first = session.vault();
        MemoryLocation second = new MemoryLocation("工作.kdbx", TestVaults.newVaultBytes("工作"));

        session.unlock(second, TestVaults.password());

        assertTrue(first.isClosed());
        assertSame(second, session.location());
        assertEquals(List.of("unlocked", "locked", "unlocked"), events);
        assertEquals(List.of("工作.kdbx", "G:/雲端/家用.kdbx"),
                recent.list().stream().map(RecentVault::location).toList());
    }

    @Test
    @DisplayName("AC-01-1：建立後接手同一個 Vault（不再解密一次，D-51），記進最近開啟與遠端雜湊")
    void adoptCreatedVaultWithoutReopening() throws Exception {
        LocalStore store = LocalStore.open(tmp, key -> null, key -> null);
        SaveCoordinator saver = new SaveCoordinator(codec, store, recent, clock);
        MemoryLocation fresh = new MemoryLocation("G:/雲端/新的.kdbx", null);
        Vault created = codec.create("新的", TestVaults.password());
        SaveResult written = saver.save(created, fresh);

        session.adopt(created, fresh, written.remoteHash());

        assertSame(created, session.vault(), "接手的是同一個物件，沒有重新開啟（重新開啟會再算一次 KDF）");
        assertSame(fresh, session.location());
        RecentVault r = recent.find("G:/雲端/新的.kdbx").orElseThrow();
        assertEquals(created.id(), r.vaultId());
        assertEquals(clock.instant(), r.lastOpened());
        assertEquals(Hashes.sha256(fresh.content()), r.remoteHash());
        assertEquals(List.of("unlocked"), events);

        session.lock();
        assertTrue(created.isClosed(), "上鎖時清零接手的保管庫");
    }

    @Test
    @DisplayName("接手時已有開啟中的保管庫：先上鎖舊的")
    void adoptLocksPrevious() throws Exception {
        session.unlock(location, TestVaults.password());
        Vault first = session.vault();
        Vault other = codec.create("工作", TestVaults.password());
        MemoryLocation where = new MemoryLocation("工作.kdbx", codec.serialize(other));

        session.adopt(other, where, Hashes.sha256(where.content()));

        assertTrue(first.isClosed());
        assertSame(other, session.vault());
        assertEquals(List.of("unlocked", "locked", "unlocked"), events);
    }

    @Test
    @DisplayName("已上鎖的 Vault 不能接手，維持上鎖、不記錄")
    void adoptRejectsClosedVault() throws Exception {
        Vault closed = codec.create("x", TestVaults.password());
        MemoryLocation where = new MemoryLocation("x.kdbx", codec.serialize(closed));
        closed.close();

        assertThrows(IllegalStateException.class, () -> session.adopt(closed, where, "hash"));
        assertFalse(session.isUnlocked());
        assertTrue(recent.list().isEmpty());
        assertTrue(events.isEmpty());
    }

    @Test
    @DisplayName("移除的監聽者不再收到通知")
    void removeListener() throws Exception {
        List<String> other = new ArrayList<>();
        VaultSession.Listener l = () -> other.add("locked");
        session.addListener(l);
        session.removeListener(l);
        session.unlock(location, TestVaults.password());
        session.lock();
        assertTrue(other.isEmpty());
    }
}
