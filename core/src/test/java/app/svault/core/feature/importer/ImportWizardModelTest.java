package app.svault.core.feature.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.feature.importer.ImportWizardModel.Busy;
import app.svault.core.feature.importer.ImportWizardModel.PreviewRow;
import app.svault.core.feature.importer.ImportWizardModel.Problem;
import app.svault.core.feature.importer.ImportWizardModel.Step;
import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 匯入精靈的狀態（T-117 合進 core；AC-14-1～AC-14-4）。外殼服務用 core 的 {@link ModuleVaultAccess}（唯讀查詢，T-32）與
 * {@link ModuleVaultWriter}（整批寫入，T-33）組成 {@link ImportHost}，session 是記憶體裡的假保管庫（密碼 probe1234）。
 * 背景與 UI 執行緒都用同步的 executor。兩個平台的接線與頁面另在各自的 {@code ImportWizardModelTest}。
 */
class ImportWizardModelTest {

    private static final String HEADER = "name,url,username,password,note\n";
    private static final String ONE = HEADER + "站台,https://a.example.com,alice,Fake-1,\n";

    @TempDir
    Path tmp;

    private final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    private final ModuleVaultWriter writer = new ModuleVaultWriter();
    private final Executor sync = Runnable::run;
    private MemoryLocation remote;
    private VaultSession session;
    private ModuleVaultAccess.Lease lease;
    private ImportHost host;
    private ImportWizardModel model;
    private int changes;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        session = TestSessions.assemble(new VaultCodec(TestVaults.fastParams()),
                LocalStore.open(tmp.resolve("local"), k -> null, k -> null), Clock.systemUTC()).session();
        session.unlock(remote, TestVaults.password());
        lease = access.open();
        lease.publish(ScreenData.of(session.vault()));
        writer.attach(lease, new ModuleVaultWriter.Target(session, sync, Clock.systemUTC(),
                out -> lease.publish(out.data())));
        host = new ImportHost() {
            @Override
            public Optional<UnlockedEntries> unlockedEntries() {
                return access.unlockedEntries();
            }

            @Override
            public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                return access.whenLoaded(executor, callback);
            }

            @Override
            public void addEntries(EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback) {
                writer.addEntries("importer", batch, executor, callback);
            }
        };
        model = newModel(host, sync);
    }

    @AfterEach
    void tearDown() {
        session.lock();
    }

    private ImportWizardModel newModel(ImportHost h, Executor background) {
        ImportWizardModel m = new ImportWizardModel(h, new ModuleGuard(ModuleFailureListener.NONE), background, sync);
        m.setListener(() -> changes++);
        return m;
    }

    private static CsvSource csv(String content) {
        return bytes(content.getBytes(StandardCharsets.UTF_8));
    }

    private static CsvSource bytes(byte[] content) {
        return new CsvSource() {
            @Override
            public InputStream open() {
                return new ByteArrayInputStream(content);
            }

            @Override
            public String name() {
                return "chrome.csv";
            }

            @Override
            public String location() {
                return "下載/chrome.csv";
            }
        };
    }

    private static CsvSource failing(RuntimeException runtime) {
        return new CsvSource() {
            @Override
            public InputStream open() throws IOException {
                if (runtime != null) {
                    throw runtime;
                }
                throw new IOException("讀不到");
            }

            @Override
            public String name() {
                return "x.csv";
            }

            @Override
            public String location() {
                return "x.csv";
            }
        };
    }

    private List<EntryView> imported() {
        List<UUID> groups = session.vault().groups().stream().filter(g -> g.name().equals("Chrome 匯入"))
                .map(GroupView::uuid).toList();
        return session.vault().entries().stream().filter(e -> groups.contains(e.groupId())).toList();
    }

    @Test
    @DisplayName("AC-14-1：選檔後到預覽，前 10 筆沒有密碼；匯入到新群組、存檔一次；完成後保留位置、放掉明文")
    void previewImportAndDone() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= 12; i++) {
            sb.append("站台 ").append(i).append(",https://s").append(i).append(".example.com/,user").append(i)
                    .append(",Fake-Pass-").append(i).append(",\n");
        }
        model.open(csv(sb.toString()));

        assertEquals(Step.PREVIEW, model.step());
        assertEquals(Busy.NONE, model.busy());
        assertEquals(12, model.total());
        assertEquals("chrome.csv", model.name());
        List<PreviewRow> preview = model.preview();
        assertEquals(10, preview.size());
        assertEquals(new PreviewRow(2, "站台 1", "user1", "https://s1.example.com/", false), preview.get(0));
        assertFalse(preview.get(0).toString().contains("user1"), "toString 不含內容");

        int writes = remote.writes();
        model.importNow();

        assertEquals(Step.DONE, model.step());
        assertEquals(12, model.imported());
        assertEquals(1, remote.writes() - writes, "整批只存檔一次");
        assertEquals(12, imported().size());
        assertEquals("下載/chrome.csv", model.location());
        assertTrue(model.preview().isEmpty(), "完成後不再持有 CSV 的內容");
        assertEquals(0, model.total());

        model.restart();
        assertEquals(Step.CHOOSE, model.step());
        assertEquals("", model.location());
        assertEquals(0, model.imported());
    }

    @Test
    @DisplayName("AC-14-2：格式錯誤的列跳過並列出行號，其餘照常匯入")
    void skippedLines() {
        model.open(csv(HEADER + "好的 1,https://a.example.com,alice,Fake-1,\n壞的,只有兩欄\n"
                + "好的 2,https://b.example.com,bob,Fake-2,\n"));
        assertEquals(List.of(3), model.skippedFormatLines());
        assertEquals(2, model.total());
        model.importNow();
        assertEquals(2, imported().size());
    }

    @Test
    @DisplayName("AC-14-4：可能重複的列預設略過（回收桶不算），選照樣匯入時全部匯入；沒有要匯入的不寫入")
    void duplicates() {
        VaultOps ops = new VaultOps(session, Clock.systemUTC());
        ops.addEntry(session.vault().id(), new EntryDraft("舊的", "Bob", "probe1234", "https://www.Shop.example.org/", ""));
        ops.addEntry(session.vault().id(), new EntryDraft("回收", "carol", "probe1234", "https://forum.example.net/", ""));
        ops.recycleEntry(session.vault().entries().stream().filter(e -> e.title().equals("回收")).findFirst()
                .orElseThrow().uuid());
        lease.publish(ScreenData.of(session.vault()));
        CsvSource file = csv(HEADER + "商店,https://shop.example.org/login,bob,Fake-1,\n"
                + "論壇,https://forum.example.net/,carol,Fake-2,\n");

        model.open(file);
        assertEquals(List.of(2), model.duplicateLines(), "回收桶裡的不算");
        assertTrue(model.preview().get(0).duplicate());
        assertTrue(model.skipDuplicates());
        assertEquals(1, model.toImport());

        int before = changes;
        model.setSkipDuplicates(false);
        model.setSkipDuplicates(false);
        assertEquals(before + 1, changes, "沒有改變時不通知");
        assertEquals(2, model.toImport());
        model.setSkipDuplicates(true);

        model.restart();
        model.open(csv(HEADER + "商店,https://shop.example.org/,bob,Fake-1,\n"));
        assertEquals(0, model.toImport());
        int writes = remote.writes();
        model.importNow();
        assertEquals(Step.PREVIEW, model.step());
        assertEquals(writes, remote.writes());
    }

    @Test
    @DisplayName("整個檔案不能匯入時留在第 1 步並說明原因；讀檔的例外只記類型")
    void fileProblems() {
        model.open(csv(""));
        assertEquals(Optional.of(Problem.EMPTY), model.problem());
        model.open(csv("title,login\nx,y\n"));
        assertEquals(Optional.of(Problem.MISSING_COLUMNS), model.problem());
        model.open(bytes((HEADER + "caf\u00e9,https://a.example.com,u,p,\n").getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(Optional.of(Problem.NOT_UTF8), model.problem());
        model.open(bytes(new byte[ChromeCsvParser.MAX_BYTES + 1]));
        assertEquals(Optional.of(Problem.TOO_LARGE), model.problem());
        model.open(failing(null));
        assertEquals(Optional.of(Problem.READ_FAILED), model.problem());
        model.open(failing(new SecurityException("權限被收回")));
        assertEquals(Optional.of(Problem.READ_FAILED), model.problem());
        model.open(failing(new IllegalStateException("意料外")));
        assertEquals(Optional.of(Problem.READ_FAILED), model.problem(), "錯誤邊界接住");
        assertEquals(Step.CHOOSE, model.step());
        assertEquals(Busy.NONE, model.busy());
    }

    @Test
    @DisplayName("已上鎖時不讀檔也不寫入；存檔失敗時一筆都不留、停在預覽，可以再試")
    void lockedAndSaveFailure() {
        model.open(csv(ONE));
        remote.failWrite(true);
        model.importNow();
        assertEquals(Step.PREVIEW, model.step());
        assertEquals(Optional.of(Problem.SAVE_FAILED), model.problem());
        assertTrue(imported().isEmpty());
        remote.failWrite(false);
        model.importNow();
        assertEquals(Step.DONE, model.step());

        // 換一列：上面那列已經匯入，會被當成可能重複而略過
        CsvSource other = csv(HEADER + "另一個,https://b.example.com,bob,Fake-2,\n");
        model.restart();
        model.open(other);
        access.revoke();
        model.importNow();
        assertEquals(Optional.of(Problem.LOCKED), model.problem());
        assertEquals(Step.CHOOSE, model.step());
        model.open(other);
        assertEquals(Optional.of(Problem.LOCKED), model.problem());
    }

    @Test
    @DisplayName("讀檔中頁面結束：晚到的結果丟掉、不通知；拿掉監聽後改狀態不會出錯")
    void clearedDropsLateResults() {
        List<Runnable> queued = new ArrayList<>();
        ImportWizardModel m = newModel(host, queued::add);
        m.open(csv(ONE));
        assertEquals(Busy.READING, m.busy());
        int before = changes;

        m.clear();
        queued.forEach(Runnable::run);

        assertEquals(before, changes);
        assertEquals(Step.CHOOSE, m.step());
        assertEquals(0, m.total());
        assertEquals("", m.name());
        m.restart();
        assertEquals(before, changes, "clear 後沒有監聽");
    }
}
