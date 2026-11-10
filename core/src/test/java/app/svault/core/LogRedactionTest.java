package app.svault.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.ClipboardPort;
import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ConflictChoices;
import app.svault.core.shell.PendingEdit;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.LogSetup;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.storage.RestoreResult;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.Settings;
import app.svault.core.sync.Conflict;
import app.svault.core.sync.MergeResult;
import app.svault.core.sync.Side;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import app.svault.core.vault.WrongPasswordException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 安全底線（CLAUDE.md、CONVENTIONS §2、STACK §10「日誌不含機密」；AC-15-1）：主密碼與條目內容不出現在日誌與
 * {@code toString()}。
 *
 * <p>做法：兩台裝置共用一個遠端，走過會寫日誌的流程（主密碼錯誤、新增與修改、衝突、衝突時上鎖、衝突暫存解不開、
 * 離線存檔失敗、重試、還原備份、剪貼簿、功能模組出錯），日誌層級開到 {@code ALL}。然後掃描兩樣東西：
 * {@link LogSetup} 寫出的 {@code logs/svault*.log}，以及在根 logger 上另掛的收集器收到的每一筆訊息（含參數，
 * 涵蓋函式庫與還沒接上檔案的 logger）；最後檢查流程中拿到的每個物件的 {@code toString()} 與例外訊息。
 * 每個流程都先確認真的有寫日誌，避免「什麼都沒記所以通過」。
 */
class LogRedactionTest {

    /** 每一個都不得出現在日誌與 {@code toString()}：主密碼、錯誤的主密碼、兩台裝置寫的每個欄位。 */
    private static final List<String> SECRETS = List.of(
            TestVaults.PASSWORD, "LR-wrong-主密碼",
            "LR標題甲", "lr-user-甲", "LR-pw-密-9Q7", "lr-site.example", "LR備註乙",
            "LR-phone-pw-41", "LR手機備註", "LR-pc-pw-52", "LR電腦備註",
            "LR編輯中-pw", "LR群組丙", "LR剪貼簿-pw", "LR模組訊息");

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"), ZoneOffset.UTC);
    private final Collector collected = new Collector();
    private final List<Object> shown = new ArrayList<>();
    private final Logger root = Logger.getLogger("");
    private Level rootLevel;
    private Level appLevel;
    private MemoryLocation remote;
    private LocalStore pcStore;
    private TestSessions.Parts pc;
    private TestSessions.Parts phone;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/我的雲端硬碟/日誌測試.kdbx", TestVaults.newVaultBytes("日誌測試"));
        pcStore = LocalStore.open(tmp.resolve("pc"), key -> null, key -> null);
        pc = TestSessions.assemble(codec, pcStore, clock);
        phone = TestSessions.assemble(codec, LocalStore.open(tmp.resolve("phone"), key -> null, key -> null), clock);

        LogSetup.install(pcStore);
        Logger app = Logger.getLogger(LogSetup.ROOT_LOGGER);
        appLevel = app.getLevel();
        rootLevel = root.getLevel();
        // 平常只記 INFO 以上；這裡全開，FINE 以下的訊息也不准帶機密
        app.setLevel(Level.ALL);
        root.setLevel(Level.ALL);
        root.addHandler(collected);
    }

    @AfterEach
    void tearDown() {
        root.removeHandler(collected);
        root.setLevel(rootLevel);
        LogSetup.uninstall();
        Logger.getLogger(LogSetup.ROOT_LOGGER).setLevel(appLevel);
        pc.session().lock();
        phone.session().lock();
    }

    @Test
    @DisplayName("AC-15-1：開啟、編輯、衝突、上鎖、存檔失敗、還原之後，日誌檔與每一筆日誌訊息都沒有主密碼與條目內容")
    void ac15_1_logsHaveNoSecrets() throws Exception {
        runEverything();

        assertTrue(collected.records.size() >= 5, "流程有寫日誌：" + collected.records.size() + " 筆");
        String files = logFiles();
        assertFalse(files.isBlank(), "LogSetup 有寫出日誌檔");
        assertNoSecrets("logs/svault*.log", files);
        for (LogRecord r : collected.records) {
            String formatted = new SimpleFormatter().formatMessage(r);
            assertNoSecrets("日誌訊息 " + r.getLoggerName(), formatted);
            if (r.getParameters() != null) {
                for (Object p : r.getParameters()) {
                    assertNoSecrets("日誌參數 " + r.getLoggerName(), String.valueOf(p));
                }
            }
        }
    }

    @Test
    @DisplayName("安全底線：流程中拿到的條目、草稿、快照、衝突、選擇、結果與例外，toString() 與例外訊息都沒有機密")
    void toStringHasNoSecrets() throws Exception {
        runEverything();

        assertTrue(shown.size() >= 20, "檢查了 " + shown.size() + " 個物件");
        for (Object o : shown) {
            String text = o instanceof Throwable t ? t.getClass().getName() + ": " + t.getMessage() : o.toString();
            assertNoSecrets(o.getClass().getName(), text);
        }
    }

    @Test
    @DisplayName("自我檢查：機密真的出現在日誌時，檢查會失敗（避免比對寫錯而永遠通過）")
    void checkCatchesLeaks() {
        Logger.getLogger("app.svault.leaky").log(Level.FINE, "不該記的：{0}", "LR-pw-密-9Q7");

        String formatted = new SimpleFormatter().formatMessage(collected.records.get(collected.records.size() - 1));
        assertThrows(AssertionError.class, () -> assertNoSecrets("範例", formatted));
        assertThrows(AssertionError.class, () -> assertNoSecrets("範例", "x" + TestVaults.PASSWORD));
    }

    // ---------- 流程 ----------

    private void runEverything() throws Exception {
        VaultSession pcSession = pc.session();

        // 主密碼錯誤：例外訊息也不能帶輸入的內容
        WrongPasswordException wrong = assertThrows(WrongPasswordException.class,
                () -> pcSession.unlock(remote, "LR-wrong-主密碼".getBytes(StandardCharsets.UTF_8)));
        shown.add(wrong);

        pcSession.unlock(remote, TestVaults.password());
        VaultOps ops = new VaultOps(pcSession, clock);
        UUID rootId = pcSession.vault().id();
        EntryDraft draft = new EntryDraft("LR標題甲", "lr-user-甲", "LR-pw-密-9Q7", "https://lr-site.example/login",
                "LR備註乙");
        shown.add(draft);
        VaultOps.Outcome added = ops.addEntry(rootId, draft);
        shown.add(added);
        UUID id = added.subject();
        shown.add(ops.addGroup(rootId, "LR群組丙"));
        shown.add(ops.load());
        for (EntryView e : pcSession.vault().entries()) {
            shown.add(e);
        }
        for (GroupView g : pcSession.vault().groups()) {
            shown.add(g);
        }
        shown.add(pcSession.vault().snapshot());
        shown.add(pcSession.vault().snapshot().entries().get(id));
        shown.add(pcSession.vault());
        shown.add(pcSession);

        // 另一台改同一筆的同一個欄位 → 這台存檔時衝突
        phone.session().unlock(remote, TestVaults.password());
        clock.advance(Duration.ofMinutes(1));
        new VaultEditor(phone.session().vault(), clock).updateEntry(id,
                new EntryDraft("LR標題甲", "lr-user-甲", "LR-phone-pw-41", "https://lr-site.example/login", "LR手機備註"));
        shown.add(phone.session().save());
        clock.advance(Duration.ofMinutes(1));
        VaultOps.Outcome conflicted = ops.updateEntry(id,
                new EntryDraft("LR標題甲", "lr-user-甲", "LR-pc-pw-52", "https://lr-site.example/login", "LR電腦備註"),
                rootId, rootId);
        shown.add(conflicted);
        MergeResult conflict = pcSession.conflict().orElseThrow();
        shown.add(conflict);
        for (Conflict c : conflict.conflicts()) {
            shown.add(c);
            shown.addAll(c.fields());
        }
        ConflictChoices choices = new ConflictChoices(ops.conflicts().orElseThrow());
        shown.add(choices.view());
        shown.add(choices.view().items().get(0));
        shown.addAll(choices.view().items().get(0).rows());
        choices.chooseAll(0, Side.LOCAL);
        shown.add(choices);

        // 衝突時上鎖：編輯畫面上還沒存的內容與選擇加密保存；然後把衝突暫存弄壞，下次解鎖會記「解不開」
        PendingEdit editing = new PendingEdit(id, new EntryDraft("LR標題甲", "lr-user-甲", "LR編輯中-pw",
                "https://lr-site.example/login", "LR電腦備註"), rootId, rootId);
        shown.add(editing);
        ops.lock(editing, choices.toMap());
        Path draftFile = pcStore.conflictFile(rootId, remote.persistentId());
        assertTrue(Files.exists(draftFile), "有衝突暫存");
        Files.write(draftFile, "壞掉的暫存".getBytes(StandardCharsets.UTF_8));

        pcSession.unlock(remote, TestVaults.password());
        ops = new VaultOps(pcSession, clock);
        shown.add(ops.refresh());
        Map<UUID, Map<String, Side>> all = Map.of(id,
                pcSession.conflict().orElseThrow().conflicts().get(0).choose(Side.LOCAL));
        shown.add(ops.resolve(all));

        // 離線時存檔失敗（日誌會記 VaultIoException 的訊息）→ 恢復後重試
        remote.offline(true);
        clock.advance(Duration.ofMinutes(1));
        VaultOps.Outcome offline = ops.updateEntry(id, new EntryDraft("LR標題甲", "lr-user-甲", "LR-pw-密-9Q7",
                "https://lr-site.example/login", "LR備註乙"), rootId, rootId);
        assertTrue(offline.saveError() != null, "離線存檔失敗");
        shown.add(offline);
        shown.add(offline.saveError());
        remote.offline(false);
        shown.add(ops.retrySave());

        // 還原一份備份（listBackups 新的在前；取有條目的那份）
        SaveCoordinator saver = pc.saver();
        List<BackupStore.Backup> backups = saver.listBackups(rootId);
        assertFalse(backups.isEmpty(), "有備份");
        RestoreResult restored = pcSession.restore(backups.get(0));
        shown.add(restored);
        int restoredEntries = pcSession.vault().entries().size();

        // 剪貼簿與功能模組的錯誤邊界
        ClipboardGuard clipboard = new ClipboardGuard(new NoopClipboard(),
                (delay, task) -> () -> { }, LiveSettings.inMemory(Settings.defaults())::current);
        clipboard.copySecret("LR剪貼簿-pw");
        clipboard.clearNow();
        shown.add(clipboard);
        new ModuleGuard(ModuleFailureListener.NONE).report("generator", "copy",
                new IllegalStateException("LR模組訊息 LR-pw-密-9Q7"));

        ops.lock(null, Map.of());
        try (Vault reopened = codec.open(remote.content(), TestVaults.password())) {
            assertEquals(restoredEntries, reopened.entries().size(), "流程跑完，遠端是還原後的內容");
            assertEquals(1, restoredEntries, "還原的備份有那筆條目");
        }
    }

    // ---------- 工具 ----------

    private String logFiles() throws Exception {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.list(pcStore.logsDir())) {
            for (Path f : files.filter(p -> p.getFileName().toString().matches("svault\\d+\\.log")).toList()) {
                all.append(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            }
        }
        return all.toString();
    }

    private static void assertNoSecrets(String where, String text) {
        for (String secret : SECRETS) {
            assertFalse(text.contains(secret), where + " 含機密「" + secret + "」：" + text);
        }
    }

    /** 收集根 logger 收到的每一筆紀錄。 */
    private static final class Collector extends Handler {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public synchronized void publish(LogRecord record) {
            records.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopClipboard implements ClipboardPort {

        @Override
        public void copy(String value, boolean sensitive) {
        }

        @Override
        public void clearSecret(String secret) {
        }
    }
}
