package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import app.svault.core.sync.SyncReport;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.VaultCodec;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 衝突對照畫面的共用邏輯（T-15；US-08）：{@link ConflictView} 列出什麼、{@link ConflictChoices} 的選擇與檢查、
 * {@link VaultOps#resolve} 寫回。兩台裝置共用一個遠端（{@link MemoryLocation}），只用假資料。
 */
class ConflictScreenTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-20T06:00:00Z"), ZoneOffset.UTC);
    private MemoryLocation remote;
    private VaultSession pc;
    private VaultSession phone;
    private VaultOps ops;
    private VaultOps phoneOps;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/S-Vault/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pc = session("pc");
        phone = session("phone");
        ops = new VaultOps(pc, clock);
        phoneOps = new VaultOps(phone, clock);
    }

    @AfterEach
    void tearDown() {
        pc.lock();
        phone.lock();
    }

    @Test
    @DisplayName("AC-08-1：只列兩邊都改了、而且改成不同值的欄位，附兩邊的值與修改時間")
    void ac08_1_onlyConflictingFieldsAreListed() {
        UUID id = shared("信箱", new EntryDraft("信箱", "rory", "old", "mail.example", "備註"));
        Instant phoneTime = clock.instant().plusSeconds(60);
        phoneEdit(id, new EntryDraft("信箱", "rory", "phone-pw", "mail.example", "手機改備註"));
        Instant pcTime = clock.instant().plusSeconds(60);
        pcEdit(id, new EntryDraft("信箱", "rory@pc", "pc-pw", "mail.example", "備註"));

        ConflictView view = ops.conflicts().orElseThrow();

        assertEquals(1, view.items().size());
        ConflictView.Item item = view.items().get(0);
        assertEquals("信箱", item.title());
        assertEquals(pcTime, item.localModified());
        assertEquals(phoneTime, item.remoteModified());
        // 帳號只有電腦改、備註只有手機改：自動合併（AC-07-3），不進對照畫面
        assertEquals(1, item.rows().size());
        ConflictView.Row row = item.rows().get(0);
        assertEquals(ConflictView.Kind.PASSWORD, row.kind());
        assertTrue(row.secret());
        assertEquals(new ConflictView.Shown(ConflictView.Shown.Form.TEXT, "pc-pw", null), row.local());
        assertEquals(new ConflictView.Shown(ConflictView.Shown.Form.TEXT, "phone-pw", null), row.remote());
        assertEquals(pcTime, row.localTime());
        assertEquals(phoneTime, row.remoteTime());
        assertFalse(item.toString().contains("pc-pw"));
        assertFalse(row.toString().contains("pc-pw"));
    }

    @Test
    @DisplayName("AC-08-1：欄位依畫面順序（名稱、帳號、密碼…）；清空的值是「空白」；所在群組顯示群組路徑")
    void ac08_1_rowOrderEmptyValuesAndGroupPath() {
        UUID id = shared("銀行", new EntryDraft("銀行", "rory", "pw", "", "備註"));
        UUID work = ops.addGroup(pc.vault().id(), "工作").subject();
        UUID home = ops.addGroup(pc.vault().id(), "家裡").subject();
        phoneOps.refresh();
        clock.advance(Duration.ofMinutes(1));
        phoneOps.updateEntry(id, new EntryDraft("銀行 B", "rory", "pw", "", "手機"), phone.vault().id(), home);
        clock.advance(Duration.ofMinutes(1));
        ops.updateEntry(id, new EntryDraft("銀行 A", "rory", "pw", "", ""), pc.vault().id(), work);

        ConflictView.Item item = ops.conflicts().orElseThrow().items().get(0);

        assertEquals(List.of(ConflictView.Kind.TITLE, ConflictView.Kind.NOTES, ConflictView.Kind.GROUP),
                item.rows().stream().map(ConflictView.Row::kind).toList());
        assertEquals("銀行 A", item.title());
        assertEquals(ConflictView.Shown.Form.EMPTY, item.rows().get(1).local().form());
        ConflictView.Row group = item.rows().get(2);
        assertEquals(new ConflictView.Shown(ConflictView.Shown.Form.GROUP, "家用 / 工作", null), group.local());
        assertEquals(new ConflictView.Shown(ConflictView.Shown.Form.GROUP, "家用 / 家裡", null), group.remote());
    }

    @Test
    @DisplayName("AC-08-2：逐欄挑選後寫回；兩台都看到選的值，沒被選的版本在歷史紀錄")
    void ac08_2_pickPerFieldAndKeepOthersInHistory() throws Exception {
        UUID id = shared("信箱", new EntryDraft("信箱", "rory", "old", "", "備註"));
        phoneEdit(id, new EntryDraft("信箱", "phone-user", "phone-pw", "", "備註"));
        pcEdit(id, new EntryDraft("信箱", "pc-user", "pc-pw", "", "備註"));
        int historyBefore = entry(pc, id).historyCount();
        ConflictChoices choices = new ConflictChoices(ops.conflicts().orElseThrow());
        choices.choose(id, "s:UserName", Side.LOCAL);
        choices.choose(id, "s:Password", Side.REMOTE);
        assertTrue(choices.complete());

        VaultOps.Outcome out = ops.resolve(choices.toMap());

        assertTrue(out.saved());
        assertEquals(VaultOps.SyncState.SYNCED, out.state(VaultOps.SyncState.CONFLICT));
        assertEquals(VaultOps.SyncState.SYNCED, ops.syncState());
        EntryView mine = entry(pc, id);
        assertEquals("pc-user", mine.username());
        assertEquals("phone-pw", mine.password());
        assertTrue(mine.historyCount() > historyBefore, "沒被選的版本推入歷史紀錄");
        phoneOps.refresh();
        EntryView theirs = entry(phone, id);
        assertEquals("pc-user", theirs.username());
        assertEquals("phone-pw", theirs.password());
    }

    @Test
    @DisplayName("AC-08-2：「全部用這台／另一台裝置的」整筆選同一邊")
    void ac08_2_chooseAll() {
        UUID id = shared("信箱", new EntryDraft("信箱", "rory", "old", "", "備註"));
        phoneEdit(id, new EntryDraft("信箱", "phone-user", "phone-pw", "", "備註"));
        pcEdit(id, new EntryDraft("信箱", "pc-user", "pc-pw", "", "備註"));
        ConflictChoices choices = new ConflictChoices(ops.conflicts().orElseThrow());

        choices.chooseAll(0, Side.REMOTE);

        assertEquals(Map.of("s:UserName", Side.REMOTE, "s:Password", Side.REMOTE), choices.toMap().get(id));
        ops.resolve(choices.toMap());
        assertEquals("phone-user", entry(pc, id).username());
        assertEquals("phone-pw", entry(pc, id).password());
    }

    @Test
    @DisplayName("AC-08-3：還沒選完不能寫回，遠端維持原狀、狀態仍是有衝突")
    void ac08_3_unfinishedChoicesDoNotWrite() {
        UUID id = shared("信箱", new EntryDraft("信箱", "rory", "old", "", ""));
        phoneEdit(id, new EntryDraft("信箱", "phone-user", "phone-pw", "", ""));
        pcEdit(id, new EntryDraft("信箱", "pc-user", "pc-pw", "", ""));
        byte[] before = remote.content();
        ConflictChoices choices = new ConflictChoices(ops.conflicts().orElseThrow());
        choices.choose(id, "s:Password", Side.LOCAL);

        assertFalse(choices.complete());
        assertEquals("s:UserName", choices.firstUnchosen(0).orElseThrow().key());
        assertThrows(IllegalArgumentException.class, () -> ops.resolve(choices.toMap()));
        assertArrayEquals(before, remote.content());
        assertEquals(VaultOps.SyncState.CONFLICT, ops.syncState());
    }

    @Test
    @DisplayName("AC-08-4：多筆衝突依序列出（第 n／N 筆），找得到第一筆還沒選完的")
    void ac08_4_severalConflicts() {
        UUID a = shared("甲", new EntryDraft("甲", "rory", "old", "", ""));
        UUID b = shared("乙", new EntryDraft("乙", "rory", "old", "", ""));
        phoneOps.refresh();
        phoneEdit(a, new EntryDraft("甲", "rory", "phone-a", "", ""));
        phoneEdit(b, new EntryDraft("乙", "rory", "phone-b", "", ""));
        clock.advance(Duration.ofMinutes(1));
        ops.updateEntry(a, new EntryDraft("甲", "rory", "pc-a", "", ""), pc.vault().id(), pc.vault().id());
        pcEdit(b, new EntryDraft("乙", "rory", "pc-b", "", ""));

        ConflictView view = ops.conflicts().orElseThrow();
        ConflictChoices choices = new ConflictChoices(view);

        assertEquals(2, view.items().size());
        assertEquals(0, choices.firstIncomplete());
        choices.chooseAll(0, Side.LOCAL);
        assertEquals(1, choices.firstIncomplete());
        choices.chooseAll(1, Side.REMOTE);
        assertTrue(choices.complete());
        assertTrue(ops.resolve(choices.toMap()).saved());
        assertEquals("pc-a", entry(pc, a).password());
        assertEquals("phone-b", entry(pc, b).password());
    }

    @Test
    @DisplayName("寫回時離線 → 尚未同步、衝突還在；再開對照畫面時沿用上次的選擇")
    void offlineResolveKeepsConflictAndChoices() {
        UUID id = shared("信箱", new EntryDraft("信箱", "rory", "old", "", ""));
        phoneEdit(id, new EntryDraft("信箱", "phone-user", "phone-pw", "", ""));
        pcEdit(id, new EntryDraft("信箱", "pc-user", "pc-pw", "", ""));
        ConflictView first = ops.conflicts().orElseThrow();
        ConflictChoices choices = new ConflictChoices(first);
        choices.chooseAll(0, Side.LOCAL);
        remote.offline(true);

        VaultOps.Outcome out = ops.resolve(choices.toMap());

        assertNotNull(out.saveError());
        assertNull(out.sync());
        assertTrue(ops.conflicts().isPresent());
        ConflictChoices again = new ConflictChoices(ops.conflicts().orElseThrow(), first, choices.toMap());
        assertTrue(again.complete());
        assertEquals(choices.toMap(), again.toMap());
    }

    @Test
    @DisplayName("另一台在對照畫面開著時又改了同一欄 → 那一欄要重新選；寫回時重新合併，又有衝突")
    void remoteChangedAgain() {
        UUID id = shared("信箱", new EntryDraft("信箱", "rory", "old", "", ""));
        phoneEdit(id, new EntryDraft("信箱", "rory", "phone-pw", "", ""));
        pcEdit(id, new EntryDraft("信箱", "rory", "pc-pw", "", ""));
        ConflictView first = ops.conflicts().orElseThrow();
        ConflictChoices choices = new ConflictChoices(first);
        choices.chooseAll(0, Side.LOCAL);
        phoneEdit(id, new EntryDraft("信箱", "rory", "phone-pw-2", "", ""));

        VaultOps.Outcome out = ops.resolve(choices.toMap());

        assertEquals(SyncReport.Status.CONFLICT, out.sync().status());
        ConflictView second = ops.conflicts().orElseThrow();
        assertEquals("phone-pw-2", second.items().get(0).rows().get(0).remote().text());
        assertFalse(new ConflictChoices(second, first, choices.toMap()).complete());
    }

    // ---------- 工具 ----------

    private VaultSession session(String name) throws Exception {
        VaultSession s = TestSessions.assemble(codec, LocalStore.open(tmp.resolve(name), k -> null, k -> null), clock)
                .session();
        s.unlock(remote, TestVaults.password());
        return s;
    }

    /** 電腦新增一筆並同步到手機（兩邊的基準都有它）。 */
    private UUID shared(String title, EntryDraft draft) {
        clock.advance(Duration.ofMinutes(1));
        UUID id = ops.addEntry(pc.vault().id(), draft).subject();
        phoneOps.refresh();
        return id;
    }

    private void phoneEdit(UUID id, EntryDraft draft) {
        clock.advance(Duration.ofMinutes(1));
        UUID group = entry(phone, id).groupId();
        assertTrue(phoneOps.updateEntry(id, draft, group, group).saved());
    }

    private void pcEdit(UUID id, EntryDraft draft) {
        clock.advance(Duration.ofMinutes(1));
        UUID group = entry(pc, id).groupId();
        VaultOps.Outcome out = ops.updateEntry(id, draft, group, group);
        assertTrue(out.sync().hasConflicts());
    }

    private static EntryView entry(VaultSession s, UUID id) {
        return ScreenData.of(s.vault()).query().all().stream().filter(e -> e.uuid().equals(id)).findFirst()
                .orElseThrow();
    }
}
