package app.svault.android.feature.importer;

import android.content.Context;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.feature.importer.CsvSource;
import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.ScopedPreferences;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

/**
 * 匯入精靈狀態測試的共用準備（T-62，同 desktop 的 T-61）：真的 core 接線——{@link ModuleVaultAccess}（唯讀查詢，T-31）與
 * {@link ModuleVaultWriter}（整批寫入，T-33），由一個照 {@code SvaultApplication} 委派的假 {@link ModuleHost} 交給模型；
 * session 是記憶體裡的假保管庫（密碼 probe1234）。背景與主執行緒都用同步的 executor，頁面本身由儀器測試驗證。
 */
abstract class ImportModelFixture {

    static final String HEADER = "name,url,username,password,note\n";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    final ModuleVaultWriter writer = new ModuleVaultWriter();
    final List<VaultOps.Outcome> redraws = new ArrayList<>();
    final Executor sync = Runnable::run;
    MemoryLocation remote;
    VaultSession session;
    ModuleVaultAccess.Lease lease;
    ModuleHost host;
    ImportWizardModel model;
    int changes;

    @Before
    public void setUpVault() throws Exception {
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        session = TestSessions.assemble(codec, LocalStore.open(tmp.newFolder("local").toPath(), k -> null,
                k -> null), Clock.systemUTC()).session();
        session.unlock(remote, TestVaults.password());
        lease = access.open();
        lease.publish(ScreenData.of(session.vault()));
        // 外殼寫入成功後換新首頁、發布新快照（SvaultApplication 經 ModuleEntriesFeed 做的事）
        writer.attach(lease, new ModuleVaultWriter.Target(session, sync, Clock.systemUTC(), out -> {
            redraws.add(out);
            lease.publish(out.data());
        }));
        host = new FakeHost();
        model = newModel(host);
    }

    @After
    public void lockVault() {
        session.lock();
    }

    ImportWizardModel newModel(ModuleHost h) {
        ImportWizardModel m = new ImportWizardModel(ImportViewModel.importHost(h),
                new ModuleGuard(ModuleFailureListener.NONE), sync, sync);
        m.setListener(() -> changes++);
        return m;
    }

    /** 記憶體裡的 CSV（不寫暫存檔）。 */
    static CsvSource csv(String content) {
        return bytes(content.getBytes(StandardCharsets.UTF_8));
    }

    static CsvSource bytes(byte[] content) {
        return new CsvSource() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(content);
            }

            @Override
            public String name() {
                return "Chrome 密碼.csv";
            }

            @Override
            public String location() {
                return "內部儲存空間/Download/Chrome 密碼.csv";
            }
        };
    }

    /** 讀不到的檔案：權限被收回時提供者丟 {@link SecurityException}，其他是 {@link IOException}。 */
    static CsvSource unreadable(RuntimeException runtime) {
        return new CsvSource() {
            @Override
            public InputStream open() throws IOException {
                if (runtime != null) {
                    throw runtime;
                }
                throw new IOException("不存在");
            }

            @Override
            public String name() {
                return "";
            }

            @Override
            public String location() {
                return "";
            }
        };
    }

    void addExisting(String title, String url, String username) {
        UUID root = session.vault().id();
        new VaultOps(session, Clock.systemUTC()).addEntry(root, new EntryDraft(title, username, "probe1234", url, ""));
        lease.publish(ScreenData.of(session.vault()));
    }

    List<EntryView> imported() {
        List<UUID> groups = session.vault().groups().stream().filter(g -> g.name().equals("Chrome 匯入"))
                .map(GroupView::uuid).toList();
        return session.vault().entries().stream().filter(e -> groups.contains(e.groupId())).toList();
    }

    /** 照 {@code SvaultApplication} 的委派：唯讀查詢與等待經 {@link ModuleVaultAccess}，寫入經 {@link ModuleVaultWriter}。 */
    class FakeHost implements ModuleHost {

        private final LiveSettings live = LiveSettings.inMemory(Settings.defaults());

        @Override
        public ModulePreferences preferences(String moduleId) {
            return new ScopedPreferences(moduleId, live::current, live::update);
        }

        @Override
        public EntryNavigator navigator(Context context) {
            throw new UnsupportedOperationException("匯入精靈不開條目");
        }

        @Override
        public Optional<UnlockedEntries> unlockedEntries() {
            return access.unlockedEntries();
        }

        @Override
        public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
            return access.whenLoaded(executor, callback);
        }

        @Override
        public void addEntries(String moduleId, EntryBatch batch, Executor executor,
                               Consumer<EntryBatchResult> callback) {
            writer.addEntries(moduleId, batch, executor, callback);
        }
    }
}
