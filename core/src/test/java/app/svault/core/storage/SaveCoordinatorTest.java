package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 安全存檔（Architecture §5 步驟 3–7）：備份、讀回驗證、寫回、本機狀態，以及每一步的故障注入。
 */
class SaveCoordinatorTest {

    /** 保管庫名稱寫在加密的內容裡；在檔案位元組中找得到它就表示有明文落地（AC-09-4）。 */
    private static final String PLAINTEXT_MARKER = "明文標記-T05-保管庫";

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T01:00:00Z"), ZoneOffset.UTC);
    private final List<Vault> opened = new ArrayList<>();
    private LocalStore store;
    private RecentStore recent;
    private Vault vault;
    private MemoryLocation remote;

    @BeforeEach
    void setUp() throws Exception {
        store = LocalStore.open(tmp, key -> null, key -> null);
        recent = new RecentStore(store.recentFile());
        vault = track(codec.create(PLAINTEXT_MARKER, TestVaults.password()));
        remote = new MemoryLocation("remote.kdbx", codec.serialize(vault));
        recent.record(new RecentVault("remote.kdbx", "remote.kdbx", vault.id(), clock.instant(),
                Hashes.sha256(remote.content())));
    }

    @AfterEach
    void closeAll() {
        opened.forEach(Vault::close);
    }

    private Vault track(Vault v) {
        opened.add(v);
        return v;
    }

    private SaveCoordinator coordinator() {
        return new SaveCoordinator(codec, store, recent, clock);
    }

    @Test
    @DisplayName("AC-09-1：每次存檔前在備份資料夾保留一份存檔前的版本")
    void backsUpPreviousVersionBeforeEverySave() throws Exception {
        SaveCoordinator saver = coordinator();
        List<byte[]> before = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            before.add(remote.content());
            SaveResult result = saver.save(vault, remote);
            assertNotNull(result.backup());
            assertArrayEquals(before.get(i), Files.readAllBytes(result.backup()), "備份是存檔前的遠端內容");
            clock.advanceSeconds(1);
        }
        List<BackupStore.Backup> backups = new BackupStore(store.backupsDir(), clock).list(vault.id());
        assertEquals(3, backups.size());
        // 最新的在前
        assertArrayEquals(before.get(2), Files.readAllBytes(backups.get(0).file()));
        assertArrayEquals(before.get(0), Files.readAllBytes(backups.get(2).file()));
    }

    @Test
    @DisplayName("AC-09-1：最多保留 10 份，超過時刪除最舊的")
    void keepsAtMostTenBackups() throws Exception {
        SaveCoordinator saver = coordinator();
        List<Path> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            created.add(saver.save(vault, remote).backup());
            clock.advanceSeconds(1);
        }
        List<BackupStore.Backup> backups = new BackupStore(store.backupsDir(), clock).list(vault.id());
        assertEquals(BackupStore.MAX_BACKUPS, backups.size());
        assertFalse(Files.exists(created.get(0)), "最舊的第 1 份已刪除");
        assertFalse(Files.exists(created.get(1)), "第 2 份已刪除");
        assertTrue(Files.exists(created.get(2)));
        assertEquals(created.get(11), backups.get(0).file());
    }

    @Test
    @DisplayName("AC-09-4：備份與原檔一樣是加密的 KDBX，不另存明文")
    void backupsAreEncryptedKdbx() throws Exception {
        SaveResult result = coordinator().save(vault, remote);
        byte[] backup = Files.readAllBytes(result.backup());
        assertKdbxWithoutPlaintext(backup);
        try (Vault reopened = codec.open(backup, TestVaults.password())) {
            assertEquals(vault.id(), reopened.id());
            assertEquals(PLAINTEXT_MARKER, reopened.name());
        }
        assertKdbxWithoutPlaintext(remote.content());
        assertKdbxWithoutPlaintext(Files.readAllBytes(store.baseFile(vault.id(), remote.persistentId())));
        // 本機資料夾裡任何檔案都不含明文標記（recent.json 只有位置與雜湊）
        try (var files = Files.walk(store.root())) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                assertFalse(contains(Files.readAllBytes(f), PLAINTEXT_MARKER), f.toString());
            }
        }
    }

    @Test
    @DisplayName("步驟 7：base/ 是剛寫入遠端的同一份位元組、遠端雜湊更新、pending/ 刪除")
    void updatesLocalState() throws Exception {
        Path pending = store.pendingFile(vault.id(), remote.persistentId());
        Files.createDirectories(pending.getParent());
        Files.write(pending, new byte[] {1, 2, 3});

        SaveResult result = coordinator().save(vault, remote);

        byte[] written = remote.content();
        assertTrue(result.localStateUpdated());
        assertArrayEquals(written, Files.readAllBytes(store.baseFile(vault.id(), remote.persistentId())));
        assertEquals(Hashes.sha256(written), result.remoteHash());
        assertEquals(Hashes.sha256(written), recent.find("remote.kdbx").orElseThrow().remoteHash());
        assertFalse(Files.exists(pending));
    }

    @Test
    @DisplayName("遠端還不存在（新建立的保管庫）時直接寫入，不做備份")
    void firstSaveWithoutRemote() throws Exception {
        MemoryLocation fresh = new MemoryLocation("new.kdbx", null);
        SaveResult result = coordinator().save(vault, fresh);
        assertNull(result.backup());
        try (Vault reopened = codec.open(fresh.content(), TestVaults.password())) {
            assertEquals(vault.id(), reopened.id());
        }
    }

    @Test
    @DisplayName("AC-09-3：讀回驗證解不開（序列化結果損壞）→ 不寫遠端、不更新本機狀態")
    void corruptSerializationIsNotWritten() throws Exception {
        byte[] original = remote.content();
        String hashBefore = recent.find("remote.kdbx").orElseThrow().remoteHash();
        SaveCoordinator saver = new SaveCoordinator(codec, v -> {
            byte[] bytes = codec.serialize(v);
            bytes[bytes.length / 2] ^= 0x5A;
            return bytes;
        }, store, recent, clock);

        assertThrows(SaveVerificationException.class, () -> saver.save(vault, remote));

        assertArrayEquals(original, remote.content());
        assertEquals(0, remote.writes());
        assertFalse(Files.exists(store.baseFile(vault.id(), remote.persistentId())));
        assertEquals(hashBefore, recent.find("remote.kdbx").orElseThrow().remoteHash());
    }

    @Test
    @DisplayName("AC-09-3：讀回的內容和記憶體中的保管庫不同 → 不寫遠端")
    void mismatchedSerializationIsNotWritten() throws Exception {
        byte[] original = remote.content();
        // 同一個主密碼的另一個保管庫：解得開，但群組（根群組 UUID）不同
        Vault other = track(codec.create("另一個", TestVaults.password()));
        SaveCoordinator saver = new SaveCoordinator(codec, v -> codec.serialize(other), store, recent, clock);

        assertThrows(SaveVerificationException.class, () -> saver.save(vault, remote));
        assertArrayEquals(original, remote.content());
    }

    @Test
    @DisplayName("C-2：傳進來的遠端和寫入前重讀的不同（剛被別人寫入）→ 不寫遠端，丟 RemoteChangedException")
    void remoteChangedBeforeReplaceIsNotOverwritten() throws Exception {
        byte[] stale = remote.content();
        Vault other = track(codec.openWithKeyOf(stale, vault));
        new app.svault.core.vault.VaultEditor(other, clock).addEntry(other.id(),
                new app.svault.core.vault.EntryDraft("別的裝置", "", "x", "", ""));
        byte[] newer = codec.serialize(other);
        remote.replace(newer);

        assertThrows(RemoteChangedException.class, () -> coordinator().write(vault, remote, stale));
        assertArrayEquals(newer, remote.content());

        MemoryLocation fresh = new MemoryLocation("new.kdbx", newer);
        assertThrows(RemoteChangedException.class, () -> coordinator().write(vault, fresh, null),
                "以為遠端不存在，其實已經有檔案");
    }

    @Test
    @DisplayName("AC-09-3：寫回遠端失敗 → 原檔不變、base/ 與遠端雜湊不更新，備份仍在")
    void failedWriteKeepsOriginal() throws Exception {
        byte[] original = remote.content();
        String hashBefore = recent.find("remote.kdbx").orElseThrow().remoteHash();
        remote.failWrite(true);

        VaultIoException e = assertThrows(VaultIoException.class, () -> coordinator().save(vault, remote));

        assertEquals(VaultIoException.Reason.IO, e.reason());
        assertArrayEquals(original, remote.content());
        assertFalse(Files.exists(store.baseFile(vault.id(), remote.persistentId())));
        assertEquals(hashBefore, recent.find("remote.kdbx").orElseThrow().remoteHash());
        assertEquals(1, new BackupStore(store.backupsDir(), clock).list(vault.id()).size());
        try (Vault reopened = codec.open(original, TestVaults.password())) {
            assertEquals(vault.id(), reopened.id());
        }
    }

    @Test
    @DisplayName("讀不到遠端（無法備份）→ 不寫遠端")
    void unreadableRemoteIsNotOverwritten() throws Exception {
        byte[] original = remote.content();
        remote.failRead(true);
        assertThrows(VaultIoException.class, () -> coordinator().save(vault, remote));
        assertArrayEquals(original, remote.content());
        assertEquals(0, remote.writes());
    }

    @Test
    @DisplayName("無法寫入備份 → 不寫遠端")
    void failedBackupStopsSave() throws Exception {
        byte[] original = remote.content();
        // 讓 backups 變成一個檔案，備份資料夾建不起來
        Files.write(store.backupsDir(), new byte[] {0});
        VaultIoException e = assertThrows(VaultIoException.class, () -> coordinator().save(vault, remote));
        assertEquals(VaultIoException.Reason.IO, e.reason());
        assertArrayEquals(original, remote.content());
        assertEquals(0, remote.writes());
    }

    @Test
    @DisplayName("遠端寫好但本機狀態更新失敗 → 存檔成功、回報本機狀態未更新")
    void localStateFailureDoesNotFailSave() throws Exception {
        // 讓 base 變成一個檔案，base/<id>.kdbx 寫不進去
        Files.write(store.root().resolve("base"), new byte[] {0});
        SaveResult result = coordinator().save(vault, remote);
        assertFalse(result.localStateUpdated());
        assertEquals(Hashes.sha256(remote.content()), result.remoteHash());
    }

    @Test
    @DisplayName("上鎖後不能存檔")
    void closedVaultCannotBeSaved() {
        vault.close();
        byte[] original = remote.content();
        assertThrows(IllegalStateException.class, () -> coordinator().save(vault, remote));
        assertArrayEquals(original, remote.content());
    }

    static void assertKdbxWithoutPlaintext(byte[] data) {
        // KDBX 的檔頭簽章 0x9AA2D903 0xB54BFB67（小端序）
        assertTrue(data.length > 8);
        assertEquals((byte) 0x03, data[0]);
        assertEquals((byte) 0xD9, data[1]);
        assertEquals((byte) 0xA2, data[2]);
        assertEquals((byte) 0x9A, data[3]);
        assertFalse(contains(data, PLAINTEXT_MARKER), "檔案中不應有明文");
    }

    static boolean contains(byte[] data, String text) {
        byte[] needle = text.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    @Test
    @DisplayName("recent.json 只記位置與雜湊，不含主密碼")
    void recentJsonHasNoSecrets() throws IOException, Exception {
        coordinator().save(vault, remote);
        String json = new String(Files.readAllBytes(store.recentFile()), StandardCharsets.UTF_8);
        assertFalse(json.contains(TestVaults.PASSWORD));
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) Json.parse(json);
        assertEquals(Long.valueOf(RecentStore.SCHEMA_VERSION), doc.get("schemaVersion"));
    }
}
