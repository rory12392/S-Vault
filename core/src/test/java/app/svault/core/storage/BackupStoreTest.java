package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 備份檔的命名、排序與保留份數（AC-09-1）。
 */
class BackupStoreTest {

    @TempDir
    Path tmp;

    private final UUID id = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    @DisplayName("檔名是 backups/<保管庫ID>/<yyyyMMdd-HHmmss>.kdbx，時間用時鐘的時區")
    void namesByLocalTime() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-17T17:30:00Z"), ZoneId.of("Asia/Taipei"));
        Path file = new BackupStore(tmp, clock).backup(id, new byte[] {1, 2});
        assertEquals(tmp.resolve(id.toString()).resolve("20261018-013000.kdbx"), file);
        assertArrayEquals(new byte[] {1, 2}, Files.readAllBytes(file));
        List<BackupStore.Backup> list = new BackupStore(tmp, clock).list(id);
        assertEquals(LocalDateTime.of(2026, 10, 18, 1, 30, 0), list.get(0).time());
    }

    @Test
    @DisplayName("同一秒存兩次不覆蓋，排序仍是最新的在前")
    void sameSecondKeepsBoth() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T01:00:00Z"), ZoneId.of("UTC"));
        BackupStore backups = new BackupStore(tmp, clock);
        Path first = backups.backup(id, new byte[] {1});
        Path second = backups.backup(id, new byte[] {2});
        Path third = backups.backup(id, new byte[] {3});
        List<BackupStore.Backup> list = backups.list(id);
        assertEquals(List.of(third, second, first), list.stream().map(BackupStore.Backup::file).toList());
    }

    @Test
    @DisplayName("AC-09-1：超過 10 份刪最舊的；不認得的檔案不動")
    void prunesOldest() throws IOException {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T01:00:00Z"), ZoneId.of("UTC"));
        BackupStore backups = new BackupStore(tmp, clock);
        Path stray = tmp.resolve(id.toString()).resolve("說明.txt");
        Files.createDirectories(stray.getParent());
        Files.write(stray, new byte[] {9});
        for (int i = 0; i < 15; i++) {
            backups.backup(id, new byte[] {(byte) i});
            clock.advanceSeconds(60);
        }
        List<BackupStore.Backup> list = backups.list(id);
        assertEquals(BackupStore.MAX_BACKUPS, list.size());
        assertArrayEquals(new byte[] {14}, Files.readAllBytes(list.get(0).file()));
        assertArrayEquals(new byte[] {5}, Files.readAllBytes(list.get(9).file()));
        assertTrue(Files.exists(stray));
    }

    @Test
    @DisplayName("沒有備份時清單是空的")
    void emptyList() throws IOException {
        assertEquals(List.of(), new BackupStore(tmp, java.time.Clock.systemUTC()).list(id));
    }
}
