package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.ConflictChoices;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 受保護的自訂欄位在衝突對照畫面也要遮罩（T-16；T-15 留下的缺口）：快照帶保護旗標（{@link EntrySnapshot#protectedKeys()}），
 * {@link ConflictView.Row#secret()} 依它決定。自訂欄位要直接用函式庫設成受保護（S-Vault 的編輯畫面沒有自訂欄位），
 * 所以放在 {@code core.vault} 套件。兩台裝置共用一個遠端（{@link MemoryLocation}），只用假資料。
 */
class ProtectedConflictTest {

    private static final String PIN = "s:PIN";
    private static final String HINT = "s:Hint";

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T06:00:00Z"), ZoneOffset.UTC);
    private final List<LogRecord> logs = new ArrayList<>();
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord r) {
            logs.add(r);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    private final Logger appLog = Logger.getLogger("app.svault");
    private Level levelBefore;
    private MemoryLocation remote;
    private VaultSession pc;
    private VaultSession phone;
    private VaultOps ops;

    @BeforeEach
    void setUp() throws Exception {
        levelBefore = appLog.getLevel();
        appLog.setLevel(Level.ALL);
        capture.setLevel(Level.ALL);
        appLog.addHandler(capture);
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pc = session("pc");
        phone = session("phone");
        ops = new VaultOps(pc, clock);
    }

    @AfterEach
    void tearDown() {
        appLog.removeHandler(capture);
        appLog.setLevel(levelBefore);
        pc.lock();
        phone.lock();
    }

    @Test
    @DisplayName("快照帶保護旗標：受保護的欄位在 protectedKeys，沒保護的不在；保護不算內容")
    void snapshotCarriesProtectionFlag() throws Exception {
        UUID id = conflictOnCustomFields();

        EntrySnapshot mine = pc.vault().snapshot().entries().get(id);

        assertTrue(mine.isProtected(PIN));
        assertTrue(mine.isProtected("s:Password"), "密碼用函式庫的保護策略寫入");
        assertFalse(mine.isProtected(HINT));
        assertFalse(mine.toString().contains("PIN"), mine.toString());
    }

    @Test
    @DisplayName("AC-08-1（T-16）：受保護的自訂欄位預設遮罩，沒保護的自訂欄位照常顯示")
    void protectedCustomFieldIsSecret() throws Exception {
        conflictOnCustomFields();

        ConflictView.Item item = ops.conflicts().orElseThrow().items().get(0);

        ConflictView.Row pin = row(item, PIN);
        ConflictView.Row hint = row(item, HINT);
        assertEquals(ConflictView.Kind.CUSTOM, pin.kind());
        assertTrue(pin.secret(), "受保護的自訂欄位要遮罩");
        assertFalse(hint.secret(), "沒保護的自訂欄位不遮罩");
        assertEquals("pc-1111", pin.local().text());
    }

    @Test
    @DisplayName("只有一邊受保護時也遮罩")
    void protectedOnOneSideIsSecret() throws Exception {
        UUID id = shared();
        clock.advance(Duration.ofMinutes(1));
        set(phone, id, "PIN", "phone-2222", true);
        phone.save();
        clock.advance(Duration.ofMinutes(1));
        set(pc, id, "PIN", "pc-1111", false);
        pc.save();

        ConflictView.Row pin = row(ops.conflicts().orElseThrow().items().get(0), PIN);

        assertTrue(pin.secret());
    }

    @Test
    @DisplayName("受保護欄位的值不出現在 toString 與日誌（含寫回時離線的警告）")
    void protectedValueNeverInToStringOrLogs() throws Exception {
        conflictOnCustomFields();
        ConflictView view = ops.conflicts().orElseThrow();
        ConflictChoices choices = new ConflictChoices(view);
        choices.chooseAll(0, Side.REMOTE);
        remote.offline(true);

        VaultOps.Outcome out = ops.resolve(choices.toMap());

        assertNotNull(out.saveError());
        ConflictView.Item item = view.items().get(0);
        StringBuilder all = new StringBuilder();
        all.append(view).append(item).append(item.rows()).append(row(item, PIN).local())
                .append(choices).append(out).append(pc.conflict().orElseThrow())
                .append(pc.conflict().orElseThrow().conflicts()).append(pc.vault().snapshot().entries().values());
        SimpleFormatter format = new SimpleFormatter();
        assertFalse(logs.isEmpty(), "離線寫回會記一筆警告，確認日誌真的有被檢查");
        for (LogRecord r : logs) {
            all.append(format.format(r));
        }
        String text = all.toString();
        for (String secret : List.of("pc-1111", "phone-2222", "PIN")) {
            assertFalse(text.contains(secret), secret + " 出現在：" + text);
        }
    }

    // ---------- 工具 ----------

    private VaultSession session(String name) throws Exception {
        VaultSession s = TestSessions.assemble(codec, LocalStore.open(tmp.resolve(name), k -> null, k -> null), clock)
                .session();
        s.unlock(remote, TestVaults.password());
        return s;
    }

    /** 電腦新增一筆有受保護的「PIN」與沒保護的「Hint」的條目，同步到手機；之後兩台都改這兩欄 → 衝突。 */
    private UUID conflictOnCustomFields() throws Exception {
        UUID id = shared();
        clock.advance(Duration.ofMinutes(1));
        set(phone, id, "PIN", "phone-2222", true);
        set(phone, id, "Hint", "phone-hint", false);
        phone.save();
        clock.advance(Duration.ofMinutes(1));
        set(pc, id, "PIN", "pc-1111", true);
        set(pc, id, "Hint", "pc-hint", false);
        assertTrue(pc.save().hasConflicts());
        return id;
    }

    private UUID shared() throws Exception {
        clock.advance(Duration.ofMinutes(1));
        UUID id = new VaultEditor(pc.vault(), clock).addEntry(pc.vault().id(),
                new EntryDraft("銀行", "rory", "pw", "", ""));
        set(pc, id, "PIN", "0000", true);
        set(pc, id, "Hint", "原本", false);
        pc.save();
        phone.refresh();
        return id;
    }

    private static void set(VaultSession s, UUID id, String name, String value, boolean secret) {
        TestVaults.setField(s.vault(), id, name, value, secret);
    }

    private static ConflictView.Row row(ConflictView.Item item, String key) {
        return item.rows().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }
}
