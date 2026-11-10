package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryBatchResult.Status;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModulePreferences;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import app.svault.desktop.shell.ext.ModuleHost;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * desktop {@link ModuleHost#addEntries} 的接線（T-33）：每個模組的 host 帶自己的代號呼叫整個程式共用的
 * {@link ModuleVaultWriter}（整批寫入的規則在 core {@code ModuleVaultWriterTest}）；只有匯入模組能寫；按下上鎖（租約作廢）
 * 後不可用；沒接上寫入的 host 與測試用的假 host 一律是上鎖。外殼的呼叫點（{@code StartFlow} → {@code ModuleVaultLink}）
 * 在 JavaFX 上，用開發版的 {@code --demo-modules} 畫面腳本驗證（dev-log）。
 */
class ModuleWriteHostTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    private final ModuleVaultWriter writer = new ModuleVaultWriter();
    private final Function<String, ModuleHost> hosts = DesktopModules.hosts(
            LiveSettings.inMemory(Settings.defaults()), Runnable::run, id -> { }, access, writer);
    private final List<EntryBatchResult> results = new ArrayList<>();
    private final List<VaultOps.Outcome> redraws = new ArrayList<>();
    private VaultSession session;

    @BeforeEach
    void setUp() throws Exception {
        MemoryLocation remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        session = TestSessions.assemble(codec, LocalStore.open(tmp, k -> null, k -> null), Clock.systemUTC())
                .session();
        session.unlock(remote, TestVaults.password());
        writer.attach(access.open(), new ModuleVaultWriter.Target(session, Runnable::run, Clock.systemUTC(),
                redraws::add));
    }

    @AfterEach
    void tearDown() {
        session.lock();
    }

    private static EntryBatch batch() {
        return new EntryBatch("Chrome 匯入", List.of(
                new EntryDraft("網站 A", "user", "probe1234", "https://a.example.com", ""),
                new EntryDraft("網站 B", "user", "probe1234", "https://b.example.com", "")));
    }

    @Test
    @DisplayName("T-33：匯入模組的 host 寫入共用的入口 → 整批存檔、外殼收到重畫用的新內容")
    void importerHostWritesThroughSharedWriter() {
        hosts.apply("importer").addEntries(batch(), Runnable::run, results::add);

        assertEquals(1, results.size());
        assertEquals(Status.SAVED, results.get(0).status());
        assertEquals(2, session.vault().entries().size());
        assertEquals(1, redraws.size());
        assertEquals(results.get(0).groupId(), redraws.get(0).subject());
    }

    @Test
    @DisplayName("T-33（D-162）：其他模組的 host 呼叫時丟 SecurityException，保管庫不變")
    void otherModulesAreRefused() {
        for (String id : List.of("generator", "health", "demo")) {
            assertThrows(SecurityException.class, () -> hosts.apply(id).addEntries(batch(), Runnable::run,
                    results::add));
        }
        assertTrue(results.isEmpty());
        assertEquals(0, session.vault().entries().size());
    }

    @Test
    @DisplayName("T-33：按下上鎖（租約作廢，LockFlow 的 revoke）之後 → LOCKED，一筆都不寫")
    void revokedLeaseIsLocked() {
        access.revoke();

        hosts.apply("importer").addEntries(batch(), Runnable::run, results::add);

        assertEquals(Status.LOCKED, results.get(0).status());
        assertEquals(0, session.vault().entries().size());
    }

    @Test
    @DisplayName("T-33：沒接上寫入的 hosts 多載與只實作舊方法的假 host → 一律 LOCKED（模組分支的測試不用改）")
    void hostsWithoutWriterAreLocked() {
        ModuleHost legacy = DesktopModules.hosts(LiveSettings.inMemory(Settings.defaults()), Runnable::run,
                id -> { }, access).apply("importer");
        ModuleHost fake = new ModuleHost() {
            @Override
            public ModulePreferences preferences() {
                return null;
            }

            @Override
            public EntryNavigator navigator() {
                return id -> { };
            }
        };

        legacy.addEntries(batch(), Runnable::run, results::add);
        fake.addEntries(batch(), Runnable::run, results::add);

        assertEquals(List.of(Status.LOCKED, Status.LOCKED), results.stream().map(EntryBatchResult::status).toList());
        assertEquals(0, session.vault().entries().size());
    }
}
