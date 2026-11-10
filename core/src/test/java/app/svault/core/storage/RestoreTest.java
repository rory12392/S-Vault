package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.WrongPasswordException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 備份還原（Architecture §5「備份還原」、AC-09-2、AC-09-5）：先確認選定的備份解得開，再備份目前的遠端、
 * 寫回備份位元組、更新本機狀態；每一步的故障注入。
 */
class RestoreTest {

    private static final String PLAINTEXT_MARKER = "明文標記-T12-還原";

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC);
    private final List<Vault> opened = new ArrayList<>();
    private LocalStore store;
    private RecentStore recent;
    private SaveCoordinator saver;
    private Vault vault;
    private MemoryLocation remote;

    @BeforeEach
    void setUp() throws Exception {
        store = LocalStore.open(tmp, key -> null, key -> null);
        recent = new RecentStore(store.recentFile());
        saver = new SaveCoordinator(codec, store, recent, clock);
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

    /** 新增一筆條目並存檔；存檔前的遠端會成為一份備份。 */
    private void addAndSave(String title) throws Exception {
        new VaultEditor(vault, clock).addEntry(vault.id(), new EntryDraft(title, "", "", "", ""));
        saver.save(vault, remote);
        clock.advanceSeconds(1);
    }

    private static List<String> titles(Vault v) {
        return v.entries().stream().map(EntryView::title).sorted().collect(Collectors.toList());
    }

    private List<BackupStore.Backup> backups() throws Exception {
        return saver.listBackups(vault.id());
    }

    // 還原後的遠端：內容（群組、條目與欄位）和備份相同；歷史與刪除紀錄可能多了還原的註記（T-27）
    private void assertSameContent(byte[] backup, byte[] restored) throws Exception {
        try (Vault b = codec.openWithKeyOf(backup, vault); Vault r = codec.openWithKeyOf(restored, vault)) {
            assertEquals(TestVaults.content(b), TestVaults.content(r), "遠端的內容是選定的備份");
        }
    }

    @Test
    @DisplayName("AC-07-9：還原時，目前有、備份沒有的條目記進刪除紀錄，被改回的條目把目前的版本留在歷史紀錄")
    void restoreMarksDeletionsAndReplacedVersions() throws Exception {
        UUID kept = new VaultEditor(vault, clock).addEntry(vault.id(), new EntryDraft("留著", "", "舊密碼", "", ""));
        saver.save(vault, remote);
        clock.advanceSeconds(1);
        new VaultEditor(vault, clock).updateEntry(kept, new EntryDraft("留著", "", "新密碼", "", ""));
        UUID added = new VaultEditor(vault, clock).addEntry(vault.id(), new EntryDraft("之後新增", "", "", "", ""));
        saver.save(vault, remote);
        clock.advanceSeconds(1);

        RestoreResult result = saver.restore(vault, remote, backups().get(0));
        track(result.vault());

        try (Vault r = codec.openWithKeyOf(remote.content(), vault)) {
            assertTrue(r.deletedObjects().stream().anyMatch(d -> d.uuid().equals(added)), "之後新增的記進刪除紀錄");
            assertEquals("舊密碼", r.entries().get(0).password());
            assertTrue(r.history(kept).stream().anyMatch(h -> h.password().equals("新密碼")), "被換掉的版本進歷史");
            assertEquals(clock.instant(), r.entries().get(0).lastModified(), "還原算一次修改");
        }
    }

    @Test
    @DisplayName("AC-09-2：選定的備份和目前內容相同時，遠端就是備份的原始位元組（不加註記）")
    void identicalBackupIsWrittenAsIs() throws Exception {
        addAndSave("一");
        saver.save(vault, remote);
        BackupStore.Backup same = backups().get(0);

        RestoreResult result = saver.restore(vault, remote, same);
        track(result.vault());

        assertArrayEquals(Files.readAllBytes(same.file()), remote.content());
    }

    @Test
    @DisplayName("AC-09-2：從備份清單選一份還原；還原前先備份目前版本，遠端變成選定備份的內容")
    void restoresSelectedBackupAfterBackingUpCurrent() throws Exception {
        addAndSave("一");
        addAndSave("二");
        addAndSave("三");
        List<BackupStore.Backup> list = backups();
        assertEquals(3, list.size());
        // 每份備份是那次存檔「前」的遠端：新到舊依序是 {一、二}、{一}、{}
        BackupStore.Backup chosen = list.get(1);
        byte[] chosenBytes = Files.readAllBytes(chosen.file());
        byte[] current = remote.content();

        RestoreResult result = saver.restore(vault, remote, chosen);
        track(result.vault());

        assertSameContent(chosenBytes, remote.content());
        assertEquals(Arrays.asList("一"), titles(result.vault()));
        assertNotNull(result.safetyBackup());
        assertArrayEquals(current, Files.readAllBytes(result.safetyBackup()), "還原前的版本有備份");
        assertEquals(4, backups().size());
        assertEquals(result.safetyBackup(), backups().get(0).file(), "還原前的備份是最新的一份");
        assertEquals(vault.id(), result.vault().id());
    }

    @Test
    @DisplayName("Architecture §5：還原後 base/ 是還原的位元組、遠端雜湊更新、pending/ 刪除")
    void updatesLocalStateAfterRestore() throws Exception {
        addAndSave("一");
        Path pending = store.pendingFile(vault.id(), remote.persistentId());
        Files.createDirectories(pending.getParent());
        Files.write(pending, new byte[] {1, 2, 3});

        RestoreResult result = saver.restore(vault, remote, backups().get(0));
        track(result.vault());

        assertTrue(result.localStateUpdated());
        assertArrayEquals(remote.content(), Files.readAllBytes(store.baseFile(vault.id(), remote.persistentId())));
        assertEquals(Hashes.sha256(remote.content()), result.remoteHash());
        assertEquals(result.remoteHash(), recent.find("remote.kdbx").get().remoteHash());
        assertFalse(Files.exists(pending));
    }

    @Test
    @DisplayName("AC-09-2：已有 10 份時還原最舊的一份；還原前的備份擠掉它也照樣還原成功")
    void restoresOldestWhenFull() throws Exception {
        for (int i = 0; i < BackupStore.MAX_BACKUPS + 1; i++) {
            addAndSave("條目" + i);
        }
        List<BackupStore.Backup> list = backups();
        assertEquals(BackupStore.MAX_BACKUPS, list.size());
        BackupStore.Backup oldest = list.get(list.size() - 1);
        byte[] oldestBytes = Files.readAllBytes(oldest.file());

        RestoreResult result = saver.restore(vault, remote, oldest);
        track(result.vault());

        assertSameContent(oldestBytes, remote.content());
        assertEquals(BackupStore.MAX_BACKUPS, backups().size());
        // 11 份中最舊的（存檔前沒有條目）已刪；留下的最舊一份是存「條目1」之前的版本
        assertEquals(Arrays.asList("條目0"), titles(result.vault()));
    }

    @Test
    @DisplayName("AC-09-5：檔案損壞（上鎖中）時用主密碼解開選定的備份再還原，損壞的檔案也先備份")
    void restoresCorruptRemoteWithPassword() throws Exception {
        addAndSave("一");
        BackupStore.Backup chosen = backups().get(0);
        byte[] chosenBytes = Files.readAllBytes(chosen.file());
        // 覆寫途中中斷：檔案被截斷
        byte[] truncated = Arrays.copyOf(remote.content(), remote.content().length / 2);
        remote.replace(truncated);

        RestoreResult result = saver.restoreWithPassword(TestVaults.password(), remote, chosen);
        track(result.vault());

        assertSameContent(chosenBytes, remote.content());
        assertEquals(vault.id(), result.vault().id());
        assertArrayEquals(truncated, Files.readAllBytes(result.safetyBackup()), "損壞的檔案也備份（不丟任何東西）");
    }

    @Test
    @DisplayName("Codex B4：金鑰衍生成本超過門檻的備份（使用者自己的）照樣能用主密碼還原，不被當成損壞")
    void costlyBackupRestores() throws Exception {
        addAndSave("一");
        BackupStore.Backup chosen = backups().get(0);
        try (app.svault.core.vault.Vault copy = codec.openWithKeyOf(Files.readAllBytes(chosen.file()), vault)) {
            copy.useKdf(new app.svault.core.vault.KdfParams(64L * 1024 * 1024, 30, 1));
            Files.write(chosen.file(), codec.serialize(copy));
        }

        RestoreResult result = saver.restoreWithPassword(TestVaults.password(), remote, chosen);
        track(result.vault());

        assertEquals(vault.id(), result.vault().id());
    }

    @Test
    @DisplayName("AC-09-5：主密碼錯誤時不寫遠端、不新增備份")
    void wrongPasswordWritesNothing() throws Exception {
        addAndSave("一");
        byte[] before = remote.content();
        int writes = remote.writes();

        assertThrows(WrongPasswordException.class, () -> saver.restoreWithPassword(
                "錯的密碼".getBytes(StandardCharsets.UTF_8), remote, backups().get(0)));

        assertArrayEquals(before, remote.content());
        assertEquals(writes, remote.writes());
        assertEquals(1, backups().size());
    }

    @Test
    @DisplayName("故障注入：選定的備份檔損壞（截斷、改壞一個位元組）時不寫遠端、不新增備份")
    void corruptBackupIsRejected() throws Exception {
        addAndSave("一");
        addAndSave("二");
        byte[] before = remote.content();
        int writes = remote.writes();
        BackupStore.Backup chosen = backups().get(1);
        byte[] good = Files.readAllBytes(chosen.file());

        Files.write(chosen.file(), Arrays.copyOf(good, good.length - 40));
        assertThrows(CorruptBackupException.class, () -> saver.restore(vault, remote, chosen));
        assertThrows(CorruptBackupException.class,
                () -> saver.restoreWithPassword(TestVaults.password(), remote, chosen));

        byte[] flipped = good.clone();
        flipped[flipped.length - 10] ^= 0x5A;
        Files.write(chosen.file(), flipped);
        assertThrows(CorruptBackupException.class, () -> saver.restore(vault, remote, chosen));

        assertArrayEquals(before, remote.content());
        assertEquals(writes, remote.writes());
        assertEquals(2, backups().size());
    }

    @Test
    @DisplayName("故障注入：備份檔已不在（被刪除）時不寫遠端")
    void missingBackupFile() throws Exception {
        addAndSave("一");
        BackupStore.Backup chosen = backups().get(0);
        Files.delete(chosen.file());
        byte[] before = remote.content();

        VaultIoException e = assertThrows(VaultIoException.class, () -> saver.restore(vault, remote, chosen));
        assertEquals(VaultIoException.Reason.NOT_FOUND, e.reason());
        assertArrayEquals(before, remote.content());
    }

    @Test
    @DisplayName("故障注入：別的保管庫的備份不能還原到這個保管庫")
    void otherVaultsBackupIsRejected() throws Exception {
        addAndSave("一");
        Vault other = track(codec.create("另一個", TestVaults.password()));
        BackupStore.Backup chosen = backups().get(0);
        Files.write(chosen.file(), codec.serialize(other));
        byte[] before = remote.content();

        assertThrows(CorruptBackupException.class,
                () -> saver.restoreWithPassword(TestVaults.password(), remote, chosen));
        assertArrayEquals(before, remote.content());
    }

    @Test
    @DisplayName("故障注入：還原中斷（寫回失敗）時遠端維持原狀、本機狀態不變")
    void interruptedWriteKeepsRemote() throws Exception {
        addAndSave("一");
        addAndSave("二");
        byte[] before = remote.content();
        byte[] base = Files.readAllBytes(store.baseFile(vault.id(), remote.persistentId()));
        String hash = recent.find("remote.kdbx").get().remoteHash();
        remote.failWrite(true);

        assertThrows(VaultIoException.class, () -> saver.restore(vault, remote, backups().get(1)));

        assertArrayEquals(before, remote.content());
        assertArrayEquals(base, Files.readAllBytes(store.baseFile(vault.id(), remote.persistentId())));
        assertEquals(hash, recent.find("remote.kdbx").get().remoteHash());
        // 還原前的備份已經寫了：多一份「目前版本」不會丟資料，再試一次也照常
        remote.failWrite(false);
        RestoreResult retry = saver.restore(vault, remote, backups().get(2));
        track(retry.vault());
        assertEquals(0, retry.vault().entries().size());
    }

    @Test
    @DisplayName("故障注入：讀不到目前的遠端（無法先備份）時不還原")
    void unreadableRemoteIsNotOverwritten() throws Exception {
        addAndSave("一");
        remote.failRead(true);
        int writes = remote.writes();

        assertThrows(VaultIoException.class, () -> saver.restore(vault, remote, backups().get(0)));
        assertEquals(writes, remote.writes());
        assertEquals(1, backups().size());
    }

    @Test
    @DisplayName("遠端檔案已不在時直接寫回，沒有還原前的備份")
    void missingRemoteIsRecreated() throws Exception {
        addAndSave("一");
        BackupStore.Backup chosen = backups().get(0);
        MemoryLocation gone = new MemoryLocation("remote.kdbx", null);

        RestoreResult result = saver.restore(vault, gone, chosen);
        track(result.vault());

        assertNull(result.safetyBackup());
        assertSameContent(Files.readAllBytes(chosen.file()), gone.content());
    }

    @Test
    @DisplayName("AC-09-4：還原後本機資料夾與遠端都沒有明文")
    void restoreLeavesNoPlaintext() throws Exception {
        addAndSave("一");
        RestoreResult result = saver.restore(vault, remote, backups().get(0));
        track(result.vault());
        SaveCoordinatorTest.assertKdbxWithoutPlaintext(remote.content());
        try (var files = Files.walk(store.root())) {
            for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                assertFalse(SaveCoordinatorTest.contains(Files.readAllBytes(f), PLAINTEXT_MARKER), f.toString());
            }
        }
    }

    @Test
    @DisplayName("上鎖的保管庫不能拿來還原")
    void closedVaultIsRejected() throws Exception {
        addAndSave("一");
        BackupStore.Backup chosen = backups().get(0);
        Vault closed = codec.create("x", TestVaults.password());
        closed.close();
        assertThrows(IllegalStateException.class, () -> saver.restore(closed, remote, chosen));
    }
}
