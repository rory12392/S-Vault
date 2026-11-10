package app.svault.desktop.feature.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.feature.importer.ImportWizardModel.Busy;
import app.svault.core.feature.importer.ImportWizardModel.PreviewRow;
import app.svault.core.feature.importer.ImportWizardModel.Problem;
import app.svault.core.feature.importer.ImportWizardModel.Step;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.shell.ScreenData;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import app.svault.desktop.shell.DesktopModules;
import app.svault.desktop.shell.ext.ModuleHost;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 匯入精靈的狀態與寫入（T-61，AC-14-1～AC-14-4 的 desktop 部分；T-117 起模型在 core，這裡測 desktop 的接線）。用真的外殼接線：
 * {@link DesktopModules#hosts} 的匯入模組 host（經 {@link ImporterDesktopFeature#importHost} 轉接）、選檔的
 * {@link CsvFile}、core 的 {@link ModuleVaultAccess}（唯讀查詢，T-32）與
 * {@link ModuleVaultWriter}（整批寫入，T-33），session 是記憶體裡的假保管庫（密碼 probe1234）。
 * 背景與畫面執行緒都用同步的 executor，頁面本身（JavaFX）由畫面腳本與截圖驗證（dev-log）。
 */
class ImportWizardModelTest {

    private static final String HEADER = "name,url,username,password,note\n";

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    private final ModuleVaultWriter writer = new ModuleVaultWriter();
    private final List<VaultOps.Outcome> redraws = new ArrayList<>();
    private final Executor sync = Runnable::run;
    private MemoryLocation remote;
    private VaultSession session;
    private ModuleVaultAccess.Lease lease;
    private ModuleHost host;
    private ImportWizardModel model;
    private int changes;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        session = TestSessions.assemble(codec, LocalStore.open(tmp.resolve("local"), k -> null, k -> null),
                Clock.systemUTC()).session();
        session.unlock(remote, TestVaults.password());
        lease = access.open();
        lease.publish(ScreenData.of(session.vault()));
        // 外殼寫入成功後重畫三欄、發布新快照（ModuleVaultLink 做的事）
        writer.attach(lease, new ModuleVaultWriter.Target(session, sync, Clock.systemUTC(), out -> {
            redraws.add(out);
            lease.publish(out.data());
        }));
        host = DesktopModules.hosts(LiveSettings.inMemory(Settings.defaults()), sync, id -> { }, access, writer)
                .apply(ImporterDesktopFeature.ID);
        model = newModel(host);
    }

    @AfterEach
    void tearDown() {
        session.lock();
    }

    private ImportWizardModel newModel(ModuleHost h) {
        ImportWizardModel m = new ImportWizardModel(ImporterDesktopFeature.importHost(h),
                new ModuleGuard(ModuleFailureListener.NONE), sync, sync);
        m.setListener(() -> changes++);
        return m;
    }

    private CsvFile csv(String content) throws Exception {
        Path p = Files.createTempFile(tmp, "chrome", ".csv");
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return new CsvFile(p);
    }

    private void addExisting(String title, String url, String username) {
        UUID root = session.vault().id();
        new VaultOps(session, Clock.systemUTC()).addEntry(root, new EntryDraft(title, username, "probe1234", url, ""));
        lease.publish(ScreenData.of(session.vault()));
    }

    private List<EntryView> imported() {
        List<UUID> groups = session.vault().groups().stream().filter(g -> g.name().equals("Chrome 匯入"))
                .map(GroupView::uuid).toList();
        return session.vault().entries().stream().filter(e -> groups.contains(e.groupId())).toList();
    }

    @Test
    @DisplayName("AC-14-1：選檔後到預覽：共 n 筆、前 10 筆的名稱／帳號／網址；預覽的列沒有密碼欄位（畫面只能顯示遮罩）")
    void previewShowsCountAndFirstTen() throws Exception {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= 12; i++) {
            sb.append("站台 ").append(i).append(",https://s").append(i).append(".example.com/,user").append(i)
                    .append(",Fake-Pass-").append(i).append(",\n");
        }
        model.open(csv(sb.toString()));

        assertEquals(Step.PREVIEW, model.step());
        assertEquals(Busy.NONE, model.busy());
        assertEquals(12, model.total());
        List<PreviewRow> preview = model.preview();
        assertEquals(10, preview.size());
        assertEquals(new PreviewRow(2, "站台 1", "user1", "https://s1.example.com/", false), preview.get(0));
        assertEquals("站台 10", preview.get(9).name());
        assertEquals(List.of("line", "name", "username", "url", "duplicate"),
                Arrays.stream(PreviewRow.class.getRecordComponents()).map(RecordComponent::getName).toList());
        assertFalse(preview.get(0).toString().contains("user1"), "toString 不含內容");
        assertEquals(12, model.toImport());
    }

    @Test
    @DisplayName("AC-14-1、AC-14-2：100 筆全部匯入新的「Chrome 匯入」群組，五個欄位與原檔一致；存檔一次")
    void importsHundredRowsIntoNewGroup() throws Exception {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= 100; i++) {
            sb.append("站台 ").append(i).append(",https://s").append(i).append(".example.com/login,user").append(i)
                    .append(",\"Fake,Pass\"\"").append(i).append("\",\"備註 ").append(i).append("\n第二行\"\n");
        }
        int writesBefore = remote.writes();
        model.open(csv(sb.toString()));
        assertEquals(100, model.toImport());

        model.importNow();

        assertEquals(Step.DONE, model.step());
        assertEquals(100, model.imported());
        assertEquals(1, remote.writes() - writesBefore, "整批只存檔一次");
        assertEquals(1, redraws.size(), "外殼重畫一次");
        List<EntryView> got = imported();
        assertEquals(100, got.size());
        EntryView e42 = got.stream().filter(e -> e.username().equals("user42")).findFirst().orElseThrow();
        assertEquals("站台 42", e42.title());
        assertEquals("https://s42.example.com/login", e42.url());
        assertEquals("Fake,Pass\"42", e42.password());
        assertEquals("備註 42\n第二行", e42.notes());
        GroupView group = session.vault().groups().stream().filter(g -> g.uuid().equals(e42.groupId()))
                .findFirst().orElseThrow();
        assertEquals(session.vault().id(), group.parentId(), "群組在根群組底下");
    }

    @Test
    @DisplayName("AC-14-2：格式錯誤的列跳過並列出行號，其餘照常匯入")
    void skippedLinesAreListed() throws Exception {
        String content = HEADER
                + "好的 1,https://a.example.com,alice,Fake-1,\n"
                + "壞的,只有兩欄\n"
                + "好的 2,https://b.example.com,bob,Fake-2,\n"
                + ",,,,\n"
                + "好的 3,https://c.example.com,carol,Fake-3,\n";
        model.open(csv(content));

        assertEquals(List.of(3, 5), model.skippedFormatLines());
        assertEquals("3、5", ImportWizardPage.lines(model.skippedFormatLines()));
        assertEquals(3, model.total());
        model.importNow();
        assertEquals(3, imported().size());
    }

    @Test
    @DisplayName("AC-14-2：行號清單超過 20 個時只列前 20 個再加「…」")
    void longLineListIsTruncated() {
        List<Integer> many = new ArrayList<>();
        for (int i = 2; i <= 40; i++) {
            many.add(i);
        }
        String s = ImportWizardPage.lines(many);
        assertTrue(s.startsWith("2、3、"));
        assertTrue(s.endsWith("21、…"), s);
    }

    @Test
    @DisplayName("AC-14-3：匯入完成後顯示原本的 CSV 路徑（提示刪除），檔案不由程式刪除；明文的解析結果已放掉")
    void doneKeepsPathAndFile() throws Exception {
        CsvFile file = csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n");
        model.open(file);
        model.importNow();

        assertEquals(Step.DONE, model.step());
        assertEquals(file.path().toString(), model.location());
        assertEquals(file.path().getFileName().toString(), model.name());
        assertTrue(Files.exists(file.path()), "不替使用者刪除 CSV");
        assertTrue(model.preview().isEmpty(), "完成後不再持有 CSV 的內容");
        assertEquals(0, model.total());
        ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.feature.importer.messages");
        assertEquals("CSV 檔中的密碼沒有加密，任何人打開都看得到。建議現在刪除這個檔案。", text.getString("done.warning"));
    }

    @Test
    @DisplayName("AC-14-4：網址網域與帳號都和既有條目相同的列標示可能重複；預設略過，選「照樣匯入」時全部匯入")
    void duplicatesSkipOrKeep() throws Exception {
        addExisting("舊的", "https://www.Shop.example.org/cart", "Bob");
        addExisting("回收桶裡的", "https://forum.example.net/", "carol");
        UUID recycledId = session.vault().entries().stream().filter(e -> e.title().equals("回收桶裡的"))
                .findFirst().orElseThrow().uuid();
        new VaultOps(session, Clock.systemUTC()).recycleEntry(recycledId);
        lease.publish(ScreenData.of(session.vault()));
        String content = HEADER
                + "商店,https://shop.example.org/login,bob,Fake-1,\n"
                + "論壇,https://forum.example.net/,carol,Fake-2,\n"
                + "另一個商店帳號,https://shop.example.org/,alice,Fake-3,\n";
        CsvFile file = csv(content);

        model.open(file);
        assertEquals(List.of(2), model.duplicateLines(), "回收桶裡的不算");
        assertTrue(model.preview().get(0).duplicate());
        assertTrue(model.skipDuplicates());
        assertEquals(2, model.toImport());
        model.importNow();
        assertEquals(Set.of("論壇", "另一個商店帳號"), imported().stream().map(EntryView::title)
                .collect(Collectors.toSet()));

        model.restart();
        model.open(file);
        model.setSkipDuplicates(false);
        assertEquals(3, model.toImport());
        model.importNow();
        assertEquals(5, imported().size(), "第二次匯入另建一個群組、照樣匯入 3 筆");
    }

    @Test
    @DisplayName("AC-14-4：全部都是可能重複又選略過時沒有要匯入的，按「匯入」不寫入")
    void nothingToImport() throws Exception {
        addExisting("舊的", "https://a.example.com", "alice");
        int writes = remote.writes();
        model.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));

        assertEquals(0, model.toImport());
        model.importNow();
        assertEquals(Step.PREVIEW, model.step());
        assertEquals(writes, remote.writes());
        assertTrue(imported().isEmpty());
    }

    @Test
    @DisplayName("存檔失敗時一筆都不留、停在預覽並說明，可以再試一次")
    void saveFailureLeavesNothing() throws Exception {
        model.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));
        remote.failWrite(true);

        model.importNow();

        assertEquals(Step.PREVIEW, model.step());
        assertEquals(Optional.of(Problem.SAVE_FAILED), model.problem());
        assertEquals(Busy.NONE, model.busy());
        assertTrue(imported().isEmpty());
        assertFalse(session.hasUnsavedChanges(), "記憶體裡也沒有半批");

        remote.failWrite(false);
        model.importNow();
        assertEquals(Step.DONE, model.step());
        assertEquals(1, imported().size());
    }

    @Test
    @DisplayName("已上鎖時不讀檔也不寫入：選檔時與按下匯入時都顯示「已上鎖」")
    void lockedIsReported() throws Exception {
        CsvFile file = csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n");
        model.open(file);
        access.revoke();

        model.importNow();
        assertEquals(Optional.of(Problem.LOCKED), model.problem());
        assertEquals(Step.CHOOSE, model.step());

        model.open(file);
        assertEquals(Optional.of(Problem.LOCKED), model.problem());
        assertEquals(Step.CHOOSE, model.step());
        assertTrue(imported().isEmpty());
    }

    @Test
    @DisplayName("整個檔案不能匯入時留在第 1 步並說明原因（空檔、缺欄位、不是 UTF-8、讀不到）")
    void fileProblems() throws Exception {
        model.open(csv(""));
        assertEquals(Optional.of(Problem.EMPTY), model.problem());
        model.open(csv("title,login\nx,y\n"));
        assertEquals(Optional.of(Problem.MISSING_COLUMNS), model.problem());
        Path latin = Files.createTempFile(tmp, "latin", ".csv");
        Files.write(latin, (HEADER + "caf\u00e9,https://a.example.com,u,p,\n").getBytes(StandardCharsets.ISO_8859_1));
        model.open(new CsvFile(latin));
        assertEquals(Optional.of(Problem.NOT_UTF8), model.problem());
        model.open(new CsvFile(tmp.resolve("不存在.csv")));
        assertEquals(Optional.of(Problem.READ_FAILED), model.problem());
        assertEquals(Step.CHOOSE, model.step());
        assertEquals(Busy.NONE, model.busy());
    }

    @Test
    @DisplayName("每種錯誤都有繁體中文訊息")
    void everyProblemHasText() {
        ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.feature.importer.messages");
        for (Problem p : Problem.values()) {
            assertFalse(text.getString(ImportWizardPage.problemKey(p)).isEmpty(), p.name());
        }
    }

    @Test
    @DisplayName("讀檔中關掉頁面：晚到的結果丟掉，不寫入")
    void closedPageDropsLateResults() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        ImportWizardModel m = new ImportWizardModel(ImporterDesktopFeature.importHost(host),
                new ModuleGuard(ModuleFailureListener.NONE), queued::add, sync);
        m.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));
        assertEquals(Busy.READING, m.busy());

        m.clear();
        queued.forEach(Runnable::run);

        assertEquals(Step.CHOOSE, m.step());
        assertEquals("", m.location());
        assertEquals(0, m.total());
    }

    @Test
    @DisplayName("讀檔時的非預期例外由模組的錯誤邊界接住，頁面顯示「無法讀取」")
    void unexpectedFailureIsContained() throws Exception {
        ModuleHost broken = new ModuleHost() {
            @Override
            public app.svault.core.module.ModulePreferences preferences() {
                return host.preferences();
            }

            @Override
            public app.svault.core.module.EntryNavigator navigator() {
                return host.navigator();
            }

            @Override
            public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                return host.whenEntriesLoaded(executor, callback);
            }

            @Override
            public Optional<app.svault.core.module.UnlockedEntries> unlockedEntries() {
                throw new IllegalStateException("故障注入");
            }
        };
        ImportWizardModel m = newModel(broken);

        m.open(csv(HEADER + "站台,https://a.example.com,alice,Fake-1,\n"));

        assertEquals(Optional.of(Problem.READ_FAILED), m.problem());
        assertEquals(Busy.NONE, m.busy());
    }
}
