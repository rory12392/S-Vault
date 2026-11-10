package app.svault.desktop.shell.start;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.BackupStore;
import app.svault.core.storage.CorruptBackupException;
import app.svault.core.storage.LocalStore;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.WrongPasswordException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 備份還原畫面背後的操作（US-09）：用真的檔案（{@code FileVaultLocation}：暫存檔＋讀回比對＋原子取代）。
 */
class RestoreActionsTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    @TempDir
    Path dir;

    private Path file;
    private VaultActions actions;
    private Clock clock = Clock.fixed(Instant.parse("2026-10-19T04:00:00Z"), TAIPEI);

    @BeforeEach
    void setUp() throws Exception {
        LocalStore store = LocalStore.open(dir.resolve("data"), k -> null, k -> null);
        // 備份檔名到秒；每次存檔用不同的時間，清單順序才固定
        Clock ticking = new Clock() {
            private long n;

            @Override
            public ZoneId getZone() {
                return TAIPEI;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public synchronized Instant instant() {
                return clock.instant().plusSeconds(n++);
            }
        };
        actions = VaultActions.withCodec(new VaultCodec(TestVaults.fastParams()), store, ticking);
        file = dir.resolve("家用.kdbx");
        actions.create("家用", file, TestVaults.password());
        addAndSave("一");
        addAndSave("二");
    }

    // 還原後的檔案：內容和備份相同；歷史與刪除紀錄可能多了還原的註記（T-27，core 的 RestoreMarks）
    private static void assertSameContent(byte[] backup, byte[] restored) throws Exception {
        VaultCodec codec = new VaultCodec(TestVaults.fastParams());
        try (Vault b = codec.open(backup, TestVaults.password()); Vault r = codec.open(restored, TestVaults.password())) {
            assertEquals(TestVaults.content(b), TestVaults.content(r), "檔案的內容是選定的備份");
        }
    }

    private void addAndSave(String title) throws Exception {
        var vault = actions.session().vault();
        new VaultEditor(vault, clock).addEntry(vault.id(), new EntryDraft(title, "", "", "", ""));
        actions.session().save();
    }

    @Test
    @DisplayName("AC-09-2：解鎖中的備份清單（新到舊），還原後開啟中的保管庫換成那個版本、檔案是備份的內容")
    void restoreWhileUnlocked() throws Exception {
        List<BackupStore.Backup> list = actions.backups();
        assertEquals(2, list.size());
        BackupStore.Backup onlyFirst = list.get(0);

        actions.restore(onlyFirst);

        assertTrue(actions.session().isUnlocked());
        assertEquals(List.of("一"), actions.session().vault().entries().stream().map(e -> e.title()).toList());
        assertSameContent(Files.readAllBytes(onlyFirst.file()), Files.readAllBytes(file));
        assertEquals(3, actions.backups().size(), "還原前的版本也備份了");
    }

    @Test
    @DisplayName("AC-09-5：檔案損壞時以最近開啟記下的 ID 找到備份，輸入主密碼後還原並直接解鎖")
    void restoreCorruptFileAndUnlock() throws Exception {
        actions.session().lock();
        byte[] good = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(good, good.length / 2));
        assertThrows(CorruptVaultException.class, () -> actions.unlock(file, TestVaults.password()));

        List<BackupStore.Backup> list = actions.backupsOf(file);
        assertEquals(2, list.size());

        actions.restoreAndUnlock(file, list.get(0), TestVaults.password());

        assertTrue(actions.session().isUnlocked());
        assertEquals(1, actions.session().vault().entries().size());
        assertSameContent(Files.readAllBytes(list.get(0).file()), Files.readAllBytes(file));
    }

    @Test
    @DisplayName("AC-09-5：主密碼錯誤時不寫檔、維持上鎖")
    void wrongPasswordKeepsFile() throws Exception {
        actions.session().lock();
        byte[] before = Files.readAllBytes(file);
        BackupStore.Backup chosen = actions.backupsOf(file).get(0);

        assertThrows(WrongPasswordException.class, () -> actions.restoreAndUnlock(file, chosen,
                "錯的".getBytes(StandardCharsets.UTF_8)));

        assertFalse(actions.session().isUnlocked());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(2, actions.backupsOf(file).size());
    }

    @Test
    @DisplayName("故障注入：備份檔損壞時不寫檔、保管庫維持原本的內容")
    void corruptBackupKeepsEverything() throws Exception {
        byte[] before = Files.readAllBytes(file);
        BackupStore.Backup chosen = actions.backups().get(0);
        Files.write(chosen.file(), new byte[] {3, 1, 4});

        assertThrows(CorruptBackupException.class, () -> actions.restore(chosen));

        assertEquals(2, actions.session().vault().entries().size());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("沒開過的檔案找不到備份（改給「重新選擇檔案」）")
    void unknownFileHasNoBackups() throws Exception {
        assertTrue(actions.backupsOf(dir.resolve("別的.kdbx")).isEmpty());
    }
}
