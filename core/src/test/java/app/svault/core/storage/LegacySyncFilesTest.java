package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T-27 的本機資料升級：T-27 之前 {@code base/}、{@code pending/} 只以保管庫 ID 命名，升級後是保管庫 ID＋位置。
 * 舊檔只搬給確定屬於它的位置，其他的原樣留著。
 */
class LegacySyncFilesTest {

    @TempDir
    Path tmp;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T08:00:00Z"), ZoneOffset.UTC);
    private final UUID id = UUID.randomUUID();
    private final MemoryLocation here = new MemoryLocation("content://drive/原檔.kdbx", null);
    private final MemoryLocation copy = new MemoryLocation("content://drive/複本.kdbx", null);
    private LocalStore store;
    private RecentStore recent;
    private SaveCoordinator saver;
    private final byte[] legacyBase = "舊基準".getBytes(StandardCharsets.UTF_8);
    private final byte[] legacyPending = "舊暫存".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void setUp() throws Exception {
        store = LocalStore.open(tmp, key -> null, key -> null);
        recent = new RecentStore(store.recentFile());
        saver = new SaveCoordinator(new VaultCodec(TestVaults.fastParams()), store, recent, clock);
        Files.createDirectories(store.legacyBaseFile(id).getParent());
        Files.createDirectories(store.legacyPendingFile(id).getParent());
        Files.write(store.legacyBaseFile(id), legacyBase);
        Files.write(store.legacyPendingFile(id), legacyPending);
    }

    private void remember(MemoryLocation where, String hash) throws Exception {
        recent.record(new RecentVault(where.persistentId(), where.displayName(), id, clock.instant(), hash));
    }

    @Test
    @DisplayName("只有一個位置用這個保管庫 ID：舊的 base/、pending/ 搬成這個位置的檔名")
    void singleLocationAdoptsLegacyFiles() throws Exception {
        remember(here, "不重要");

        assertArrayEquals(legacyBase, saver.readBase(id, here));
        assertArrayEquals(legacyPending, saver.readPending(id, here));
        assertFalse(Files.exists(store.legacyBaseFile(id)));
        assertFalse(Files.exists(store.legacyPendingFile(id)));
        assertTrue(Files.exists(store.baseFile(id, here.persistentId())));
    }

    @Test
    @DisplayName("兩個位置共用保管庫 ID：舊基準只給記下的遠端雜湊相同的那個位置，另一個當成沒有基準")
    void sharedIdAdoptsByHash() throws Exception {
        remember(here, "別的雜湊");
        remember(copy, Hashes.sha256(legacyBase));

        assertNull(saver.readBase(id, here), "不是這個位置的基準");
        assertNull(saver.readPending(id, here));
        assertTrue(Files.exists(store.legacyBaseFile(id)), "留給另一個位置");

        assertArrayEquals(legacyBase, saver.readBase(id, copy));
        assertArrayEquals(legacyPending, saver.readPending(id, copy));
        assertFalse(Files.exists(store.legacyBaseFile(id)));
    }

    @Test
    @DisplayName("都不屬於時舊檔原樣留著（pending/ 可能有還沒寫出去的修改，不刪）")
    void unclaimedLegacyFilesAreKept() throws Exception {
        remember(here, "雜湊一");
        remember(copy, "雜湊二");

        assertNull(saver.readBase(id, here));
        assertNull(saver.readBase(id, copy));
        assertArrayEquals(legacyBase, Files.readAllBytes(store.legacyBaseFile(id)));
        assertArrayEquals(legacyPending, Files.readAllBytes(store.legacyPendingFile(id)));
    }

    @Test
    @DisplayName("新檔名已經有基準時不覆蓋（只新增、不改既有的）")
    void existingNewFileIsNotOverwritten() throws Exception {
        remember(here, "不重要");
        byte[] current = "新基準".getBytes(StandardCharsets.UTF_8);
        Path base = store.baseFile(id, here.persistentId());
        Files.write(base, current);

        assertArrayEquals(current, saver.readBase(id, here));
        assertTrue(Files.exists(store.legacyBaseFile(id)));
    }
}
