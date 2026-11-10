package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code recent.json}（AC-06-3 的資料部分）。
 */
class RecentStoreTest {

    @TempDir
    Path tmp;

    private static RecentVault entry(String location, long epochSecond, String hash) {
        return new RecentVault(location, "名稱 " + location, UUID.nameUUIDFromBytes(location.getBytes(
                StandardCharsets.UTF_8)), Instant.ofEpochSecond(epochSecond), hash);
    }

    @Test
    @DisplayName("AC-06-3：記住最近開啟的位置與遠端雜湊，重新讀檔後還在")
    void remembersAcrossRestarts() throws IOException {
        Path file = tmp.resolve("recent.json");
        RecentVault v = entry("G:\\我的雲端硬碟\\家用.kdbx", 1_000, "ab".repeat(32));
        new RecentStore(file).record(v);

        List<RecentVault> list = new RecentStore(file).list();
        assertEquals(List.of(v), list);
        assertEquals(Optional.of(v), new RecentStore(file).find(v.location()));
    }

    @Test
    @DisplayName("同一位置再開一次：取代舊紀錄並移到最前面")
    void reopenMovesToFront() throws IOException {
        RecentStore store = new RecentStore(tmp.resolve("recent.json"));
        store.record(entry("a", 1, null));
        store.record(entry("b", 2, null));
        store.record(entry("a", 3, "cd".repeat(32)));
        List<RecentVault> list = store.list();
        assertEquals(List.of("a", "b"), list.stream().map(RecentVault::location).toList());
        assertEquals(Instant.ofEpochSecond(3), list.get(0).lastOpened());
    }

    @Test
    @DisplayName("最多記 10 筆，最舊的移除")
    void capsEntries() throws IOException {
        RecentStore store = new RecentStore(tmp.resolve("recent.json"));
        for (int i = 0; i < 12; i++) {
            store.record(entry("v" + i, i, null));
        }
        List<RecentVault> list = store.list();
        assertEquals(RecentStore.MAX_ENTRIES, list.size());
        assertEquals("v11", list.get(0).location());
        assertEquals("v2", list.get(9).location());
    }

    @Test
    @DisplayName("更新遠端雜湊不改變順序；沒有該位置時不做事；可以移除")
    void updateHashAndRemove() throws IOException {
        RecentStore store = new RecentStore(tmp.resolve("recent.json"));
        store.record(entry("a", 1, null));
        store.record(entry("b", 2, null));
        store.updateRemoteHash("a", "ef".repeat(32));
        store.updateRemoteHash("none", "00".repeat(32));
        assertEquals("ef".repeat(32), store.find("a").orElseThrow().remoteHash());
        assertEquals(List.of("b", "a"), store.list().stream().map(RecentVault::location).toList());
        store.remove("b");
        assertEquals(List.of("a"), store.list().stream().map(RecentVault::location).toList());
    }

    @Test
    @DisplayName("壞掉的檔案或欄位不完整的紀錄被略過，不丟例外")
    void toleratesBadContent() throws IOException {
        Path file = tmp.resolve("recent.json");
        Files.write(file, ("{\"schemaVersion\":1,\"vaults\":[{\"location\":\"x\"},"
                + "{\"location\":\"ok\",\"displayName\":\"ok\",\"vaultId\":\"" + UUID.randomUUID()
                + "\",\"lastOpened\":\"2026-10-18T00:00:00Z\"}]}").getBytes(StandardCharsets.UTF_8));
        List<RecentVault> list = new RecentStore(file).list();
        assertEquals(1, list.size());
        assertEquals("ok", list.get(0).location());
        assertEquals(null, list.get(0).remoteHash());

        Files.write(file, "[".getBytes(StandardCharsets.UTF_8));
        assertTrue(new RecentStore(file).list().isEmpty());
    }

    @Test
    @DisplayName("沒有檔案時清單是空的，也不建立檔案")
    void emptyWithoutFile() {
        Path file = tmp.resolve("recent.json");
        assertTrue(new RecentStore(file).list().isEmpty());
        assertFalse(Files.exists(file));
    }
}
