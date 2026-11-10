package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.shell.ConflictChoices;
import app.svault.core.shell.PendingEdit;
import app.svault.core.shell.VaultOps;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 上鎖前存檔與衝突暫存（T-18；AC-12-3、AC-08-5）：兩台裝置共用一個遠端，上鎖一律經 {@link VaultOps#lock}。
 */
class LockSaveTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T02:00:00Z"), ZoneOffset.UTC);
    private MemoryLocation remote;
    private SyncDevice pc;
    private SyncDevice phone;
    private VaultOps ops;

    @BeforeEach
    void setUp() throws Exception {
        remote = new MemoryLocation("H:/我的雲端硬碟/家用.kdbx", TestVaults.newVaultBytes("家用"));
        pc = new SyncDevice(tmp.resolve("pc"), codec, remote, clock);
        phone = new SyncDevice(tmp.resolve("phone"), codec, remote, clock);
        pc.unlock();
        phone.unlock();
        ops = new VaultOps(pc.session, clock);
    }

    @AfterEach
    void tearDown() {
        pc.session.lock();
        phone.session.lock();
    }

    // ---------- AC-12-3 ----------

    @Test
    @DisplayName("AC-12-3：編輯畫面中還沒按儲存的新條目，上鎖前先存進遠端，然後上鎖並清零")
    void ac12_3_unsavedNewEntryIsSavedBeforeLock() throws Exception {
        UUID root = pc.session.vault().id();
        Vault open = pc.session.vault();

        ops.lock(new PendingEdit(null, new EntryDraft("編輯中", "rory", "e1", "", ""), root, root),
                Map.of());

        assertFalse(pc.session.isUnlocked());
        assertTrue(open.isClosed(), "金鑰材料已清零");
        assertEquals(List.of("編輯中"), titlesOf(remote.content()));
    }

    @Test
    @DisplayName("AC-12-3：修改到一半的既有條目，上鎖前先存（移到另一個群組也一起）")
    void ac12_3_unsavedEditIsSavedBeforeLock() throws Exception {
        UUID id = pc.add("網銀", "old");
        UUID root = pc.session.vault().id();
        UUID[] group = new UUID[1];
        pc.editAndSave(e -> group[0] = e.addGroup(root, "銀行"));

        ops.lock(new PendingEdit(id, new EntryDraft("網銀", "rory", "new", "", "改到一半"), root, group[0]),
                Map.of());

        try (Vault v = codec.open(remote.content(), TestVaults.password())) {
            EntryView e = v.entries().stream().filter(x -> x.uuid().equals(id)).findFirst().orElseThrow();
            assertEquals("new", e.password());
            assertEquals("改到一半", e.notes());
            assertEquals(group[0], e.groupId());
        }
    }

    @Test
    @DisplayName("AC-12-3：沒有名稱的編輯內容不能存（同儲存按鈕的檢查），照常上鎖")
    void ac12_3_draftWithoutNameIsDropped() throws Exception {
        UUID root = pc.session.vault().id();
        int writes = remote.writes();

        ops.lock(new PendingEdit(null, new EntryDraft("  ", "rory", "e1", "", ""), root, root), Map.of());

        assertFalse(pc.session.isUnlocked());
        assertEquals(writes, remote.writes());
    }

    @Test
    @DisplayName("AC-12-3：離線時上鎖 → 修改寫進 pending/ 照常上鎖，下次解鎖還在")
    void ac12_3_offlineLockKeepsChangesInPending() throws Exception {
        UUID root = pc.session.vault().id();
        UUID vaultId = root;
        remote.offline(true);

        ops.lock(new PendingEdit(null, new EntryDraft("離線新增", "rory", "o1", "", ""), root, root),
                Map.of());

        assertFalse(pc.session.isUnlocked());
        assertTrue(Files.exists(pc.pendingFile(vaultId)));
        remote.offline(false);
        pc.unlock();
        assertEquals(List.of("離線新增"), pc.titles());
    }

    @Test
    @DisplayName("AC-12-3：上一次存檔失敗的修改，上鎖前再存一次")
    void ac12_3_failedSaveIsRetriedBeforeLock() throws Exception {
        remote.offline(true);
        UUID root = pc.session.vault().id();
        ops.addEntry(root, new EntryDraft("失敗過", "rory", "f1", "", ""));
        assertTrue(pc.session.hasUnsavedChanges());
        remote.offline(false);

        ops.lock(null, Map.of());

        assertEquals(List.of("失敗過"), titlesOf(remote.content()));
    }

    // ---------- AC-08-5 ----------

    @Test
    @DisplayName("AC-08-5：衝突沒解決時上鎖 → 本機修改與選擇以同一主密碼加密保存；位元組不含條目內容與主密碼")
    void ac08_5_conflictStateIsEncrypted() throws Exception {
        UUID id = conflictOnPasswordAndNotes();
        Map<String, Side> chosen = pc.session.conflict().orElseThrow().conflicts().get(0).choose(Side.REMOTE);
        UUID vaultId = pc.session.vault().id();

        ops.lock(null, Map.of(id, chosen));

        Path draft = pc.store.conflictFile(vaultId, remote.persistentId());
        assertTrue(Files.exists(draft), "衝突的選擇");
        assertTrue(Files.exists(pc.pendingFile(vaultId)), "本機的修改");
        for (Path file : List.of(draft, pc.pendingFile(vaultId))) {
            byte[] bytes = Files.readAllBytes(file);
            for (String secret : List.of("共用", "rory", "pc-pw", "phone-pw", "電腦的備註", "手機的備註", "probe1234",
                    "Password", "Notes")) {
                assertFalse(contains(bytes, secret.getBytes(StandardCharsets.UTF_8)), file + " 有明文：" + secret);
                assertFalse(contains(bytes, secret.getBytes(StandardCharsets.UTF_16LE)), file + " 有明文：" + secret);
            }
            assertThrows(WrongPasswordException.class,
                    () -> codec.open(bytes, "wrong".getBytes(StandardCharsets.UTF_8)).close());
            codec.open(bytes, TestVaults.password()).close();
        }
    }

    @Test
    @DisplayName("AC-08-5：下次解鎖、更新後回到衝突，帶回上鎖前的選擇；只帶回一次")
    void ac08_5_unlockResumesConflictWithChoices() throws Exception {
        UUID id = conflictOnPasswordAndNotes();
        Conflict c = pc.session.conflict().orElseThrow().conflicts().get(0);
        String passwordKey = c.fields().get(0).key();
        ops.lock(null, Map.of(id, Map.of(passwordKey, Side.REMOTE)));

        pc.unlock();
        ops = new VaultOps(pc.session, clock);
        assertEquals(VaultOps.SyncState.CONFLICT, ops.refresh().state(VaultOps.SyncState.SYNCED));
        Optional<ConflictChoices> resumed = ops.resumeConflicts();

        assertTrue(resumed.isPresent(), "回到衝突對照畫面");
        assertEquals(Side.REMOTE, resumed.get().get(id, passwordKey));
        assertEquals(1, resumed.get().view().items().size());
        assertFalse(ops.resumeConflicts().isPresent(), "之後由 Banner 的「處理」開啟");
    }

    @Test
    @DisplayName("AC-08-5：另一台在這之間又改了某個欄位 → 那個欄位的選擇不沿用，其他的沿用")
    void ac08_5_changedFieldIsChosenAgain() throws Exception {
        UUID id = conflictOnPasswordAndNotes();
        Map<String, Side> all = pc.session.conflict().orElseThrow().conflicts().get(0).choose(Side.LOCAL);
        ops.lock(null, Map.of(id, all));
        phone.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "phone-pw", "", "手機又改了")));

        pc.unlock();
        ops = new VaultOps(pc.session, clock);
        ops.refresh();
        ConflictChoices resumed = ops.resumeConflicts().orElseThrow();

        int kept = 0;
        for (Map.Entry<String, Side> e : all.entrySet()) {
            if (resumed.get(id, e.getKey()) != null) {
                kept++;
            }
        }
        assertEquals(all.size() - 1, kept, "只有備註要重新選");
        assertFalse(resumed.complete());
    }

    @Test
    @DisplayName("AC-08-5：衝突解決並寫回後刪掉暫存的選擇")
    void ac08_5_draftIsDeletedAfterResolve() throws Exception {
        UUID id = conflictOnPasswordAndNotes();
        Map<String, Side> chosen = pc.session.conflict().orElseThrow().conflicts().get(0).choose(Side.LOCAL);
        UUID vaultId = pc.session.vault().id();
        ops.lock(null, Map.of(id, chosen));
        pc.unlock();
        ops = new VaultOps(pc.session, clock);
        ops.refresh();
        ConflictChoices resumed = ops.resumeConflicts().orElseThrow();

        VaultOps.Outcome out = ops.resolve(resumed.toMap());

        assertTrue(out.saved());
        assertFalse(Files.exists(pc.store.conflictFile(vaultId, remote.persistentId())));
        assertFalse(Files.exists(pc.pendingFile(vaultId)));
    }

    @Test
    @DisplayName("AC-08-5：解鎖後讀不到遠端（離線）時不知道有沒有衝突：暫存留著，下次上鎖也不會被清掉")
    void ac08_5_offlineUnlockKeepsDraft() throws Exception {
        UUID id = conflictOnPasswordAndNotes();
        Map<String, Side> chosen = pc.session.conflict().orElseThrow().conflicts().get(0).choose(Side.LOCAL);
        UUID vaultId = pc.session.vault().id();
        ops.lock(null, Map.of(id, chosen));
        pc.unlock();
        remote.offline(true);
        ops = new VaultOps(pc.session, clock);
        ops.refresh();
        assertFalse(ops.resumeConflicts().isPresent());
        ops.lock(null, Map.of());
        remote.offline(false);

        pc.unlock();
        ops = new VaultOps(pc.session, clock);
        ops.refresh();

        assertEquals(chosen.size(), ops.resumeConflicts().orElseThrow().toMap().get(id).size());
        assertTrue(Files.exists(pc.store.conflictFile(vaultId, remote.persistentId())));
    }

    @Test
    @DisplayName("沒有衝突時上鎖不寫衝突暫存")
    void noConflictNoDraft() throws Exception {
        pc.add("一般", "n1");
        UUID vaultId = pc.session.vault().id();

        ops.lock(null, Map.of());

        assertFalse(Files.exists(pc.store.conflictFile(vaultId, remote.persistentId())));
        pc.unlock();
        assertNull(new VaultOps(pc.session, clock).resumeConflicts().orElse(null));
    }

    /** 兩台在上次同步後都改了同一筆的密碼與備註（不同的值）→ 電腦存檔時衝突（AC-08-1）。 */
    private UUID conflictOnPasswordAndNotes() throws Exception {
        UUID id = pc.add("共用", "old");
        phone.session.refresh();
        phone.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "phone-pw", "", "手機的備註")));
        clock.advance(Duration.ofMinutes(1));
        pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("共用", "rory", "pc-pw", "", "電腦的備註")));
        assertTrue(pc.session.conflict().isPresent());
        return id;
    }

    private List<String> titlesOf(byte[] data) throws Exception {
        try (Vault v = codec.open(data, TestVaults.password())) {
            List<String> out = new ArrayList<>();
            for (EntryView e : v.entries()) {
                out.add(e.title());
            }
            out.sort(null);
            return out;
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
