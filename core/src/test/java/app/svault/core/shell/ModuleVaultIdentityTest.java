package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.MasterPasswordCheck;
import app.svault.core.module.MasterPasswordCheck.Status;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 給功能模組的「目前解鎖的保管庫 ID」與「確認主密碼」（T-35，BIO 的啟用：支撐 AC-11-1、AC-11-2）：綁在唯讀查詢的同一份
 * 租約，上鎖當下就不可用；只開放給生物辨識模組；不保留主密碼、不記錄；每次解鎖最多錯 5 次。只用假資料。
 */
class ModuleVaultIdentityTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T06:00:00Z"), ZoneOffset.UTC);
    private final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    private final ModuleVaultIdentity identity = new ModuleVaultIdentity();
    private final List<MasterPasswordCheck> results = new ArrayList<>();
    private final List<Runnable> vaultQueue = new ArrayList<>();
    private VaultSession session;
    private ModuleVaultAccess.Lease lease;
    private String vaultId;

    @BeforeEach
    void setUp() throws Exception {
        MemoryLocation remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        session = TestSessions.assemble(codec, LocalStore.open(tmp.resolve("phone"), k -> null, k -> null), clock)
                .session();
        session.unlock(remote, TestVaults.password());
        vaultId = session.vault().id().toString();
        lease = access.open();
        identity.attach(lease, new ModuleVaultIdentity.Target(session, vaultQueue::add));
    }

    @AfterEach
    void tearDown() {
        session.lock();
    }

    @Test
    @DisplayName("T-35：解鎖中回傳保管庫 ID（根群組 UUID，和 BIO 的 Keystore 別名、recent.json 同一個）")
    void vaultIdWhileUnlocked() {
        assertEquals(Optional.of(vaultId), identity.vaultId());
    }

    @Test
    @DisplayName("T-35：還沒接上、按下上鎖當下（租約作廢）、session 上鎖後都為空；再解鎖要重新接上")
    void vaultIdEmptyWhenLocked() {
        assertEquals(Optional.empty(), new ModuleVaultIdentity().vaultId());
        access.revoke();
        assertEquals(Optional.empty(), identity.vaultId());

        ModuleVaultAccess.Lease next = access.open();
        assertEquals(Optional.empty(), identity.vaultId());
        identity.attach(next, new ModuleVaultIdentity.Target(session, vaultQueue::add));
        assertEquals(Optional.of(vaultId), identity.vaultId());
        session.lock();
        assertEquals(Optional.empty(), identity.vaultId());
    }

    @Test
    @DisplayName("T-35：主密碼正確 → MATCH 並帶保管庫 ID；在保管庫執行緒比對，結果交給模組的 executor 一次")
    void rightPasswordMatches() {
        List<Runnable> moduleQueue = new ArrayList<>();
        identity.verifyMasterPassword("biometric", TestVaults.password(), moduleQueue::add, results::add);

        assertEquals(1, vaultQueue.size());
        assertEquals(List.of(), moduleQueue);
        drain();
        assertEquals(List.of(), results);
        moduleQueue.forEach(Runnable::run);
        assertEquals(List.of(MasterPasswordCheck.matched(vaultId)), results);
        assertTrue(results.get(0).matched());
        assertEquals(Optional.of(vaultId), results.get(0).confirmedVaultId());
    }

    @Test
    @DisplayName("T-35：主密碼錯誤 → MISMATCH，不帶保管庫 ID")
    void wrongPasswordMismatches() {
        verify(bytes("probe12345"));

        assertEquals(List.of(MasterPasswordCheck.rejected(Status.MISMATCH)), results);
        assertEquals(Optional.empty(), results.get(0).confirmedVaultId());
        assertFalse(results.get(0).matched());
    }

    @Test
    @DisplayName("T-35：呼叫回傳後就把陣列清零，比對結果不受影響（不保留主密碼）")
    void callerMayWipeRightAfterCall() {
        byte[] password = TestVaults.password();
        identity.verifyMasterPassword("biometric", password, Runnable::run, results::add);
        Arrays.fill(password, (byte) 0);
        drain();

        assertEquals(List.of(MasterPasswordCheck.matched(vaultId)), results);
    }

    @Test
    @DisplayName("T-35：沒有接上、租約已作廢（含排進執行緒之後才上鎖）、session 已上鎖 → LOCKED")
    void lockedCases() {
        new ModuleVaultIdentity().verifyMasterPassword("biometric", TestVaults.password(), Runnable::run,
                results::add);

        identity.verifyMasterPassword("biometric", TestVaults.password(), Runnable::run, results::add);
        access.revoke();
        drain();

        identity.verifyMasterPassword("biometric", TestVaults.password(), Runnable::run, results::add);

        ModuleVaultAccess.Lease next = access.open();
        identity.attach(next, new ModuleVaultIdentity.Target(session, vaultQueue::add));
        identity.verifyMasterPassword("biometric", TestVaults.password(), Runnable::run, results::add);
        session.lock();
        drain();

        MasterPasswordCheck locked = MasterPasswordCheck.rejected(Status.LOCKED);
        assertEquals(List.of(locked, locked, locked, locked), results);
    }

    @Test
    @DisplayName("T-35：同一次解鎖錯 5 次後一律 TOO_MANY_ATTEMPTS（連對的也不比對），重新解鎖才重算")
    void attemptLimitPerUnlock() {
        for (int i = 0; i < ModuleVaultIdentity.MAX_MISMATCHES; i++) {
            verify(bytes("wrong" + i));
        }
        verify(TestVaults.password());

        assertEquals(ModuleVaultIdentity.MAX_MISMATCHES + 1, results.size());
        assertEquals(MasterPasswordCheck.rejected(Status.MISMATCH), results.get(0));
        assertEquals(MasterPasswordCheck.rejected(Status.TOO_MANY_ATTEMPTS), results.get(results.size() - 1));

        identity.attach(access.open(), new ModuleVaultIdentity.Target(session, vaultQueue::add));
        results.clear();
        verify(TestVaults.password());
        assertEquals(List.of(MasterPasswordCheck.matched(vaultId)), results);
    }

    @Test
    @DisplayName("T-35：對了不算錯的次數；錯 4 次再對仍是 MATCH")
    void matchDoesNotCountAsMismatch() {
        for (int i = 0; i < ModuleVaultIdentity.MAX_MISMATCHES - 1; i++) {
            verify(bytes("wrong" + i));
        }
        verify(TestVaults.password());
        verify(TestVaults.password());

        assertEquals(MasterPasswordCheck.matched(vaultId), results.get(results.size() - 1));
        assertEquals(MasterPasswordCheck.matched(vaultId), results.get(results.size() - 2));
    }

    @Test
    @DisplayName("T-35：只開放給生物辨識模組；其他代號與 null 丟 SecurityException，不比對、不通知")
    void onlyBiometric() {
        assertEquals(Set.of("biometric"), ModuleVaultIdentity.VERIFY_MODULES);
        for (String other : Arrays.asList("importer", "autofill", "health", "generator", "Biometric", null)) {
            assertThrows(SecurityException.class, () -> identity.verifyMasterPassword(other,
                    TestVaults.password(), Runnable::run, results::add), String.valueOf(other));
        }
        assertEquals(List.of(), vaultQueue);
        assertEquals(List.of(), results);
    }

    @Test
    @DisplayName("T-35：參數 null 丟 NullPointerException")
    void nullArguments() {
        Executor direct = Runnable::run;
        assertThrows(NullPointerException.class,
                () -> identity.verifyMasterPassword("biometric", null, direct, results::add));
        assertThrows(NullPointerException.class,
                () -> identity.verifyMasterPassword("biometric", TestVaults.password(), null, results::add));
        assertThrows(NullPointerException.class,
                () -> identity.verifyMasterPassword("biometric", TestVaults.password(), direct, null));
        assertThrows(NullPointerException.class, () -> new ModuleVaultIdentity.Target(null, direct));
        assertThrows(NullPointerException.class, () -> new ModuleVaultIdentity.Target(session, null));
    }

    @Test
    @DisplayName("T-35：保管庫執行緒不收（程式正在結束）→ LOCKED；回呼丟例外只記錄類型")
    void rejectedThreadAndThrowingCallback() {
        identity.attach(lease, new ModuleVaultIdentity.Target(session, r -> {
            throw new RejectedExecutionException();
        }));
        identity.verifyMasterPassword("biometric", TestVaults.password(), Runnable::run, results::add);
        assertEquals(List.of(MasterPasswordCheck.rejected(Status.LOCKED)), results);

        identity.attach(lease, new ModuleVaultIdentity.Target(session, Runnable::run));
        identity.verifyMasterPassword("biometric", TestVaults.password(), Runnable::run, r -> {
            throw new IllegalStateException("模組的錯誤");
        });
        identity.verifyMasterPassword("biometric", TestVaults.password(), r -> {
            throw new RejectedExecutionException();
        }, results::add);
        assertEquals(1, results.size());
    }

    @Test
    @DisplayName("T-35：日誌只有模組代號與結果，沒有主密碼、保管庫 ID 以外的內容")
    void logsNoSecret() {
        Logger logger = Logger.getLogger(ModuleVaultIdentity.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level old = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            verify(TestVaults.password());
            verify(bytes("probe12345"));
            assertThrows(SecurityException.class, () -> identity.verifyMasterPassword("importer",
                    TestVaults.password(), Runnable::run, results::add));
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(old);
        }
        assertFalse(records.isEmpty());
        for (LogRecord r : records) {
            StringBuilder text = new StringBuilder(r.getMessage());
            if (r.getParameters() != null) {
                for (Object p : r.getParameters()) {
                    text.append(' ').append(p);
                }
            }
            assertFalse(text.toString().contains(TestVaults.PASSWORD), text.toString());
            assertFalse(text.toString().contains("probe12345"), text.toString());
            assertFalse(text.toString().contains(vaultId), text.toString());
        }
    }

    @Test
    @DisplayName("T-35：MATCH 一定要有保管庫 ID；rejected 不能是 MATCH")
    void resultShape() {
        assertThrows(IllegalArgumentException.class, () -> MasterPasswordCheck.rejected(Status.MATCH));
        assertThrows(NullPointerException.class, () -> MasterPasswordCheck.matched(null));
    }

    private void verify(byte[] password) {
        identity.verifyMasterPassword("biometric", password, Runnable::run, results::add);
        drain();
    }

    private void drain() {
        List<Runnable> now = new ArrayList<>(vaultQueue);
        vaultQueue.clear();
        now.forEach(Runnable::run);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
