package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.KdfCost;
import app.svault.core.vault.KdfParams;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultFormat;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 金鑰衍生太弱時提示並可一鍵升級（L-4，D-187）：升級換成 S-Vault 預設（AC-01-8）的參數與新的鹽，寫回遠端，
 * 不需要再輸入主密碼；內容不變。
 */
class WeakKdfUpgradeTest {

    @TempDir
    Path tmp;

    private final VaultCodec weakCodec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-10T02:00:00Z"), ZoneOffset.UTC);
    private SyncDevice pc;

    @AfterEach
    void tearDown() {
        if (pc != null) {
            pc.session.lock();
        }
    }

    @Test
    @DisplayName("L-4：1 MiB、1 次的檔案判定為偏弱；S-Vault 預設與 KeePassXC 的一般設定不算")
    void weakness() {
        assertTrue(KdfCost.isWeak(new VaultFormat(4, "AES-256", "Argon2d", 1L << 20, 1, 1, "ChaCha20")));
        assertTrue(KdfCost.isWeak(new VaultFormat(4, "AES-256", "AES-KDF", 0, 60_000, 0, "Salsa20")),
                "KeePass 舊版預設 6 萬輪");
        assertTrue(KdfCost.isWeak(new VaultFormat(4, "AES-256", "Argon2d", 8L << 20, 100, 2, "ChaCha20")),
                "記憶體太小，迭代再多 GPU 也好算");
        assertFalse(KdfCost.isWeak(new VaultFormat(4, "AES-256", "Argon2d", KdfParams.DEFAULT.memoryBytes(),
                KdfParams.DEFAULT.iterations(), KdfParams.DEFAULT.parallelism(), "ChaCha20")));
        assertFalse(KdfCost.isWeak(new VaultFormat(4, "AES-256", "AES-KDF", 0, 6_000_000, 0, "Salsa20")));
    }

    @Test
    @DisplayName("Codex B3：按升級時遠端剛被另一台改過 → 合併寫回的是遠端的參數，回報沒有生效（不誤報成功）")
    void upgradeLostToConcurrentChangeIsReported() throws Exception {
        MemoryLocation remote = new MemoryLocation("H:/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pc = new SyncDevice(tmp.resolve("pc"), weakCodec, remote, clock);
        SyncDevice phone = new SyncDevice(tmp.resolve("phone"), weakCodec, remote, clock);
        pc.unlock();
        phone.unlock();
        phone.add("手機新增", "p1");
        try {
            assertFalse(app.svault.core.session.KdfUpgrade.run(pc.session));
            assertTrue(KdfCost.isWeak(pc.session.vault().format()));
            assertEquals(List.of("手機新增"), pc.titles(), "另一台的修改照樣合併進來");
        } finally {
            phone.session.lock();
        }
    }

    @Test
    @DisplayName("L-4：升級後遠端是 Argon2d 64 MiB、2 次、新的鹽，用同一個主密碼打得開，條目不變")
    void upgradeRewritesKdf() throws Exception {
        MemoryLocation remote = new MemoryLocation("H:/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pc = new SyncDevice(tmp.resolve("pc"), weakCodec, remote, clock);
        pc.unlock();
        pc.add("條目", "p1");
        assertTrue(KdfCost.isWeak(pc.session.vault().format()));

        assertTrue(app.svault.core.session.KdfUpgrade.run(pc.session));

        assertFalse(KdfCost.isWeak(pc.session.vault().format()));
        try (Vault reopened = new VaultCodec().open(remote.content(), TestVaults.password())) {
            VaultFormat f = reopened.format();
            assertEquals("Argon2d", f.kdf());
            assertEquals(KdfParams.DEFAULT.memoryBytes(), f.memoryBytes());
            assertEquals(KdfParams.DEFAULT.iterations(), f.iterations());
            assertEquals(List.of("條目"), reopened.entries().stream().map(e -> e.title()).toList());
        }
    }
}
