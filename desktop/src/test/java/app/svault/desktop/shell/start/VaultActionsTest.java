package app.svault.desktop.shell.start;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.Hashes;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.UnsupportedKdfException;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultFormat;
import app.svault.core.vault.WrongPasswordException;
import app.svault.desktop.platform.FileVaultLocation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 歡迎、建立、解鎖畫面背後的操作（US-01、US-06）。只用暫存資料夾裡以程式建立的保管庫與 core 的假資料測試檔。
 */
class VaultActionsTest {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-18T04:05:00Z"), TAIPEI);

    @TempDir
    Path dir;

    private LocalStore store;
    private VaultActions actions;

    @BeforeEach
    void setUp() throws IOException {
        store = LocalStore.open(dir.resolve("data"), k -> null, k -> null);
        actions = VaultActions.withCodec(new VaultCodec(TestVaults.fastParams()), store, CLOCK);
    }

    @Test
    @DisplayName("AC-01-1：建立後是 KDBX 4、能用同一個主密碼開啟，並直接解鎖、記進最近開啟")
    void createWritesKdbxAndUnlocks() throws Exception {
        Path file = dir.resolve("vaults").resolve("我的密碼.kdbx");
        Files.createDirectories(file.getParent());

        actions.create("我的密碼", file, TestVaults.password());

        assertTrue(actions.session().isUnlocked());
        assertEquals("我的密碼", actions.session().vault().name());
        UUID id = actions.session().vault().id();
        try (Vault reopened = new VaultCodec().open(Files.readAllBytes(file), TestVaults.password())) {
            assertEquals(4, reopened.format().kdbxVersion());
            assertEquals(id, reopened.id());
        }
        List<RecentItem> recent = actions.recent();
        assertEquals(1, recent.size());
        assertEquals("我的密碼", recent.get(0).name());
        assertEquals("2026/10/18 12:05", recent.get(0).lastOpened());
        // 寫入走 SaveCoordinator：base/ 有同一份位元組（之後的三方合併基準）
        String location = new FileVaultLocation(file).persistentId();
        assertArrayEquals(Files.readAllBytes(file), Files.readAllBytes(store.baseFile(id, location)));
    }

    @Test
    @DisplayName("建立後由 VaultSession 接手（D-51）：記下的遠端雜湊是寫出的檔案，上鎖會關掉建立的保管庫")
    void createAdoptsCreatedVault() throws Exception {
        Path file = dir.resolve("adopt.kdbx");

        actions.create("adopt", file, TestVaults.password());

        Vault opened = actions.session().vault();
        assertEquals(Hashes.sha256(Files.readAllBytes(file)),
                new RecentStore(store.recentFile()).list().get(0).remoteHash());
        actions.session().lock();
        assertTrue(opened.isClosed());
    }

    @Test
    @DisplayName("AC-01-8：正式程式建立的保管庫是 Argon2d、64 MiB、2 次、平行度 4、AES-256")
    void standardUsesAc018Parameters() throws Exception {
        Path file = dir.resolve("正式參數.kdbx");
        VaultActions standard = VaultActions.standard(store, CLOCK);

        standard.create("正式參數", file, TestVaults.password());

        assertEquals(new VaultFormat(4, "AES-256", "Argon2d", 64L * 1024 * 1024, 2, 4, "ChaCha20"),
                standard.session().vault().format());
    }

    @Test
    @DisplayName("AC-01-3：主密碼錯誤時拋出 WrongPasswordException、維持上鎖，檔案一個位元組都不變")
    void wrongPasswordLeavesFileUntouched() throws Exception {
        Path file = dir.resolve("a.kdbx");
        actions.create("a", file, TestVaults.password());
        actions.session().lock();
        byte[] before = Files.readAllBytes(file);

        assertThrows(WrongPasswordException.class,
                () -> actions.unlock(file, "wrong".getBytes(StandardCharsets.UTF_8)));

        assertFalse(actions.session().isUnlocked());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("AC-01-7：Argon2id 的檔案拋出 UnsupportedKdfException，不閃退、不改動檔案")
    void argon2idIsRejectedWithoutChanges() throws Exception {
        Path file = dir.resolve("argon2id.kdbx");
        Files.write(file, TestVaults.fixture(TestVaults.KXC_ARGON2ID));
        byte[] before = Files.readAllBytes(file);

        UnsupportedKdfException e = assertThrows(UnsupportedKdfException.class,
                () -> actions.unlock(file, TestVaults.password()));

        assertEquals("unlock.error.kdf", OpenProblem.of(e).messageKey());
        assertFalse(actions.session().isUnlocked());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertTrue(actions.recent().isEmpty(), "開不了的檔案不記進最近開啟");
    }

    @Test
    @DisplayName("AC-06-4：檔案被刪除時拋出 NOT_FOUND，畫面顯示「找不到這個檔案…」與重新選擇")
    void missingFileIsNotFound() {
        Path file = dir.resolve("deleted.kdbx");

        VaultIoException e = assertThrows(VaultIoException.class, () -> actions.unlock(file, TestVaults.password()));

        assertEquals(VaultIoException.Reason.NOT_FOUND, e.reason());
        OpenProblem p = OpenProblem.of(e);
        assertEquals("open.error.notFound", p.messageKey());
        assertTrue(p.chooseAgain());
    }

    @Test
    @DisplayName("KeePassXC 建立的檔案（相容測試檔）能解鎖，最近開啟只記位置")
    void unlocksKeePassXcFixture() throws Exception {
        Path file = dir.resolve("kxc.kdbx");
        Files.write(file, TestVaults.fixture(TestVaults.KXC_ARGON2D_AES));

        actions.unlock(file, TestVaults.password());

        assertTrue(actions.session().isUnlocked());
        String json = Files.readString(store.recentFile());
        assertFalse(json.contains(TestVaults.PASSWORD), "recent.json 不含主密碼");
    }

    @Test
    @DisplayName("建立失敗（寫不進去：上層是檔案不是資料夾）時不留下檔案、維持上鎖")
    void createFailureLeavesNothing() throws IOException {
        Path blocker = Files.write(dir.resolve("not-a-folder"), new byte[] {1});
        Path file = blocker.resolve("x.kdbx");

        assertThrows(VaultIoException.class, () -> actions.create("x", file, TestVaults.password()));

        assertFalse(Files.exists(file));
        assertFalse(actions.session().isUnlocked());
    }

    @Test
    @DisplayName("AC-06-3：最近開啟清單最新的在前；從清單移除不刪檔")
    void recentOrderAndForget() throws Exception {
        Path first = dir.resolve("first.kdbx");
        Path second = dir.resolve("second.kdbx");
        actions.create("first", first, TestVaults.password());
        actions.create("second", second, TestVaults.password());

        List<RecentItem> recent = actions.recent();
        assertEquals(List.of("second", "first"), recent.stream().map(RecentItem::name).toList());

        actions.forget(recent.get(0));

        assertEquals(List.of("first"), actions.recent().stream().map(RecentItem::name).toList());
        assertTrue(Files.exists(second));
    }
}
