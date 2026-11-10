package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MutableClock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;

/**
 * {@link VaultEditor}：新增、修改（歷史版本）、資源回收桶、永久刪除、群組（AC-02-1～AC-02-4）。
 * 每個寫入都以序列化後重新開啟確認「存得進檔案」，不只看記憶體。
 */
class VaultEditorTest {

    /** 帶毫秒的起始時間：確認寫進模型的時間都截到秒（KDBX 4 的精度，STACK §13）。 */
    private static final Instant START = Instant.parse("2026-10-18T08:00:00.789Z");

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(START, ZoneOffset.UTC);
    private Vault vault;
    private VaultEditor editor;

    @BeforeEach
    void open() {
        vault = codec.create("編輯測試", TestVaults.password());
        editor = new VaultEditor(vault, clock);
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    @DisplayName("AC-02-1：新增的條目出現在所屬群組，重新開啟後仍存在，密碼以受保護的值儲存")
    void addEntryPersists() throws Exception {
        UUID work = editor.addGroup(vault.id(), "工作");
        UUID id = editor.addEntry(work, new EntryDraft("GitHub", "rory", "s3cret-密碼", "https://github.com", "備註"));

        try (Vault reopened = reopen()) {
            EntryView e = entry(reopened, id);
            assertEquals(work, e.groupId());
            assertEquals("GitHub", e.title());
            assertEquals("rory", e.username());
            assertEquals("s3cret-密碼", e.password());
            assertEquals("https://github.com", e.url());
            assertEquals("備註", e.notes());
            assertEquals(START.getEpochSecond(), e.created().getEpochSecond());
            assertEquals(0, e.created().getNano(), "時間要截到秒");
            assertEquals(e.created(), e.lastModified());
            assertEquals(0, e.historyCount());
            assertTrue(property(reopened, id, Entry.STANDARD_PROPERTY_NAME_PASSWORD).isProtected(),
                    "密碼要以 Protected 儲存（P15）");
        }
    }

    @Test
    @DisplayName("AC-02-1：名稱是空的或群組不存在時拒絕新增")
    void addEntryValidates() {
        assertThrows(IllegalArgumentException.class,
                () -> editor.addEntry(vault.id(), new EntryDraft("  ", "u", "p", "", "")));
        assertThrows(IllegalArgumentException.class,
                () -> editor.addEntry(UUID.randomUUID(), new EntryDraft("名稱", "u", "p", "", "")));
        assertTrue(vault.entries().isEmpty());
    }

    @Test
    @DisplayName("AC-02-2：編輯後修改時間更新為注入時鐘的時間，舊值推入歷史紀錄並存進檔案")
    void updatePushesHistory() throws Exception {
        UUID id = editor.addEntry(vault.id(), new EntryDraft("舊名稱", "old", "old-pass", "https://a", "舊備註"));
        clock.advanceSeconds(90);

        assertTrue(editor.updateEntry(id, new EntryDraft("新名稱", "new", "new-pass", "https://b", "新備註")));

        try (Vault reopened = reopen()) {
            EntryView e = entry(reopened, id);
            assertEquals("新名稱", e.title());
            assertEquals("new-pass", e.password());
            assertEquals(START.getEpochSecond() + 90, e.lastModified().getEpochSecond());
            assertEquals(0, e.lastModified().getNano());
            assertEquals(START.getEpochSecond(), e.created().getEpochSecond(), "建立時間不變");
            assertEquals(1, e.historyCount());
            JacksonEntry old = history(reopened, id).get(0);
            assertEquals("舊名稱", old.getTitle());
            assertEquals("old", old.getUsername());
            assertEquals("old-pass", old.getPassword());
            assertEquals("舊備註", old.getNotes());
            assertEquals(id, old.getUuid(), "歷史版本與條目同一個 UUID（KDBX 規格）");
            assertEquals(START.getEpochSecond(), old.getLastModificationTime().toInstant().getEpochSecond(),
                    "歷史版本保留舊的修改時間");
            assertTrue(old.getPropertyValue(Entry.STANDARD_PROPERTY_NAME_PASSWORD).isProtected(),
                    "歷史版本裡的密碼也要受保護");
        }
    }

    @Test
    @DisplayName("AC-02-2：內容沒有改變時不產生歷史版本、不更新修改時間")
    void unchangedUpdateIsNoop() {
        UUID id = editor.addEntry(vault.id(), new EntryDraft("名稱", "u", "p", "", ""));
        clock.advanceSeconds(10);

        assertFalse(editor.updateEntry(id, new EntryDraft("名稱", "u", "p", null, null)));

        EntryView e = entry(vault, id);
        assertEquals(0, e.historyCount());
        assertEquals(START.getEpochSecond(), e.lastModified().getEpochSecond());
    }

    @Test
    @DisplayName("AC-02-2：歷史版本超過 HistoryMaxItems 時刪掉最舊的")
    void historyIsTrimmedToMaxItems() throws Exception {
        assertEquals(10, vault.historyMaxItems());
        UUID id = editor.addEntry(vault.id(), new EntryDraft("v0", "", "", "", ""));
        for (int i = 1; i <= 12; i++) {
            clock.advanceSeconds(1);
            editor.updateEntry(id, new EntryDraft("v" + i, "", "", "", ""));
        }
        try (Vault reopened = reopen()) {
            assertEquals(10, entry(reopened, id).historyCount());
            List<String> titles = history(reopened, id).stream().map(JacksonEntry::getTitle)
                    .collect(Collectors.toList());
            assertEquals("v2", titles.get(0), "最舊的 v0、v1 被刪掉");
            assertEquals("v11", titles.get(9));
        }
    }

    @Test
    @DisplayName("AC-02-2（P15）：原本受保護的欄位（例如被設成 Protected 的備註）修改後仍受保護")
    void updateKeepsProtectionFlag() throws Exception {
        UUID id = editor.addEntry(vault.id(), new EntryDraft("名稱", "u", "p", "", "秘密備註"));
        JacksonDatabase db = vault.database();
        JacksonEntry raw = db.findEntry(id);
        raw.setPropertyValue(Entry.STANDARD_PROPERTY_NAME_NOTES,
                db.getPropertyValueStrategy().newProtected().of("秘密備註"));

        editor.updateEntry(id, new EntryDraft("名稱", "u", "p", "", "改過的秘密備註"));

        try (Vault reopened = reopen()) {
            assertEquals("改過的秘密備註", entry(reopened, id).notes());
            assertTrue(property(reopened, id, Entry.STANDARD_PROPERTY_NAME_NOTES).isProtected());
            assertFalse(property(reopened, id, Entry.STANDARD_PROPERTY_NAME_TITLE).isProtected(),
                    "沒有要求保護的欄位維持不保護（和 KeePassXC 相同）");
        }
    }

    @Test
    @DisplayName("AC-02-2：KeePassXC 檔裡已有 3 個歷史版本的條目，修改後變成 4 個，自訂欄位與附件保留")
    void updateOnKeePassXcFixture() throws Exception {
        try (Vault kxc = codec.open(TestVaults.fixture(TestVaults.KXC_GROUPS_RECYCLE_HISTORY),
                TestVaults.password())) {
            EntryView before = kxc.entries().stream().filter(e -> e.historyCount() == 3).findFirst().orElseThrow();
            VaultEditor kxcEditor = new VaultEditor(kxc, clock);

            assertTrue(kxcEditor.updateEntry(before.uuid(), new EntryDraft(before.title(), before.username(),
                    "S-Vault 改的密碼", before.url(), before.notes())));

            try (Vault reopened = codec.open(codec.serialize(kxc), TestVaults.password())) {
                EntryView after = entry(reopened, before.uuid());
                assertEquals(4, after.historyCount());
                assertEquals("S-Vault 改的密碼", after.password());
                assertEquals(before.customFields(), after.customFields());
                assertEquals(before.attachmentNames(), after.attachmentNames());
                assertEquals(before.tags(), after.tags());
                assertEquals(before.password(), history(reopened, before.uuid()).get(3).getPassword());
            }
        }
    }

    @Test
    @DisplayName("AC-02-3：刪除後條目移到資源回收桶（不存在時建立），還原回原本的群組")
    void recycleAndRestoreEntry() throws Exception {
        UUID work = editor.addGroup(vault.id(), "工作");
        UUID id = editor.addEntry(work, new EntryDraft("要刪的", "u", "p", "", ""));
        clock.advanceSeconds(5);

        assertTrue(editor.recycleEntry(id));

        UUID bin;
        try (Vault reopened = reopen()) {
            GroupView recycleBin = reopened.groups().stream().filter(GroupView::recycleBin).findFirst().orElseThrow();
            bin = recycleBin.uuid();
            assertEquals(reopened.id(), recycleBin.parentId(), "資源回收桶在根群組下");
            assertEquals(bin, entry(reopened, id).groupId());
        }
        assertThrows(IllegalArgumentException.class, () -> editor.recycleEntry(id), "已在回收桶裡的不能再丟");

        editor.restoreEntry(id);
        try (Vault reopened = reopen()) {
            assertEquals(work, entry(reopened, id).groupId());
            assertEquals(1, reopened.groups().stream().filter(GroupView::recycleBin).count(), "回收桶只建立一次");
        }
        assertThrows(IllegalArgumentException.class, () -> editor.restoreEntry(id), "不在回收桶裡的不能還原");
    }

    @Test
    @DisplayName("AC-02-3：原本的群組也不在了時，還原到根群組")
    void restoreFallsBackToRoot() {
        UUID temp = editor.addGroup(vault.id(), "暫時");
        UUID id = editor.addEntry(temp, new EntryDraft("條目", "", "", "", ""));
        editor.recycleEntry(id);
        editor.recycleGroup(temp);
        editor.deleteGroupPermanently(temp);

        editor.restoreEntry(id);

        assertEquals(vault.id(), entry(vault, id).groupId());
    }

    @Test
    @DisplayName("AC-02-3：資源回收桶中的條目可以永久刪除，並記進 DeletedObjects（合併時才不會復活）")
    void deletePermanentlyRecordsDeletedObject() throws Exception {
        UUID id = editor.addEntry(vault.id(), new EntryDraft("條目", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> editor.deleteEntryPermanently(id),
                "不在回收桶裡的不能直接永久刪除");
        editor.recycleEntry(id);
        clock.advanceSeconds(30);

        editor.deleteEntryPermanently(id);

        try (Vault reopened = reopen()) {
            assertTrue(reopened.entries().stream().noneMatch(e -> e.uuid().equals(id)));
            assertEquals(List.of(new DeletedObjectView(id, Instant.parse("2026-10-18T08:00:30Z"))),
                    reopened.deletedObjects());
        }
    }

    @Test
    @DisplayName("AC-02-3：KeePassXC 檔原有的 2 筆 DeletedObjects 保留，新的一筆接在後面")
    void deletedObjectsAreAppended() throws Exception {
        try (Vault kxc = codec.open(TestVaults.fixture(TestVaults.KXC_DELETED_OBJECTS), TestVaults.password())) {
            List<DeletedObjectView> before = kxc.deletedObjects();
            assertEquals(2, before.size());
            UUID id = kxc.entries().get(0).uuid();
            VaultEditor kxcEditor = new VaultEditor(kxc, clock);
            kxcEditor.recycleEntry(id);
            kxcEditor.deleteEntryPermanently(id);

            try (Vault reopened = codec.open(codec.serialize(kxc), TestVaults.password())) {
                List<DeletedObjectView> after = reopened.deletedObjects();
                assertEquals(3, after.size());
                assertEquals(before, after.subList(0, 2));
                assertEquals(id, after.get(2).uuid());
            }
        }
    }

    @Test
    @DisplayName("AC-02-3：檔案停用資源回收桶時（KeePassXC 的設定），刪除就是永久刪除")
    void recycleBinDisabledDeletesPermanently() {
        KdbxModelAccess.file(vault.database()).meta.recycleBinEnabled = false;
        UUID id = editor.addEntry(vault.id(), new EntryDraft("條目", "", "", "", ""));

        assertFalse(editor.recycleEntry(id));

        assertTrue(vault.entries().isEmpty());
        assertTrue(vault.groups().stream().noneMatch(GroupView::recycleBin));
        assertEquals(id, vault.deletedObjects().get(0).uuid());
    }

    @Test
    @DisplayName("AC-02-4：新增、重新命名群組，把條目移到其他群組；群組的修改時間不因放入條目而改變")
    void groupsAndMove() throws Exception {
        UUID a = editor.addGroup(vault.id(), "甲");
        UUID b = editor.addGroup(a, "乙");
        UUID id = editor.addEntry(a, new EntryDraft("條目", "", "", "", ""));
        Instant bCreated = group(vault, b).lastModified();
        clock.advanceSeconds(60);

        editor.renameGroup(a, "甲改名");
        editor.moveEntry(id, b);

        try (Vault reopened = reopen()) {
            assertEquals("甲改名", group(reopened, a).name());
            assertEquals(START.getEpochSecond() + 60, group(reopened, a).lastModified().getEpochSecond());
            assertEquals(a, group(reopened, b).parentId());
            assertEquals(bCreated, group(reopened, b).lastModified(), "條目移進來不算群組被修改");
            assertEquals(b, entry(reopened, id).groupId());
            assertEquals(START.getEpochSecond(), entry(reopened, id).lastModified().getEpochSecond(),
                    "移動只改 LocationChanged，不改修改時間（和 KeePassXC 相同）");
        }
        assertThrows(IllegalArgumentException.class, () -> editor.renameGroup(a, " "));
        assertThrows(IllegalArgumentException.class, () -> editor.renameGroup(vault.id(), "根"),
                "根群組名稱就是保管庫名稱，不在這裡改");
    }

    @Test
    @DisplayName("AC-02-4：刪除含條目的群組時整個移到資源回收桶，可以還原；永久刪除時群組與條目都記進 DeletedObjects")
    void recycleRestoreAndDeleteGroup() throws Exception {
        UUID a = editor.addGroup(vault.id(), "甲");
        UUID b = editor.addGroup(a, "乙");
        UUID e1 = editor.addEntry(a, new EntryDraft("一", "", "", "", ""));
        UUID e2 = editor.addEntry(b, new EntryDraft("二", "", "", "", ""));
        assertEquals(2, EntryQuery.of(vault).countInGroup(a), "刪除前提示的條目數（含子群組）");

        assertTrue(editor.recycleGroup(a));
        UUID bin = vault.groups().stream().filter(GroupView::recycleBin).findFirst().orElseThrow().uuid();
        assertEquals(bin, group(vault, a).parentId());
        assertTrue(EntryQuery.of(vault).all().isEmpty(), "回收桶裡的條目不算在清單裡");

        editor.restoreGroup(a);
        assertEquals(vault.id(), group(vault, a).parentId());
        assertEquals(2, EntryQuery.of(vault).all().size());

        editor.recycleGroup(a);
        editor.deleteGroupPermanently(a);
        try (Vault reopened = reopen()) {
            assertTrue(reopened.groups().stream().noneMatch(g -> g.uuid().equals(a) || g.uuid().equals(b)));
            assertTrue(reopened.entries().isEmpty());
            List<UUID> deleted = reopened.deletedObjects().stream().map(DeletedObjectView::uuid)
                    .sorted().collect(Collectors.toList());
            assertEquals(List.of(a, b, e1, e2).stream().sorted().collect(Collectors.toList()), deleted);
        }
    }

    @Test
    @DisplayName("AC-02-4：根群組與資源回收桶本身不能刪除，不能把群組或條目移進回收桶以外的方式放進回收桶")
    void protectsRootAndRecycleBin() {
        UUID id = editor.addEntry(vault.id(), new EntryDraft("條目", "", "", "", ""));
        editor.recycleEntry(id);
        UUID bin = vault.groups().stream().filter(GroupView::recycleBin).findFirst().orElseThrow().uuid();

        assertThrows(IllegalArgumentException.class, () -> editor.recycleGroup(vault.id()));
        assertThrows(IllegalArgumentException.class, () -> editor.recycleGroup(bin));
        assertThrows(IllegalArgumentException.class, () -> editor.addGroup(bin, "放不進去"));
        UUID other = editor.addEntry(vault.id(), new EntryDraft("另一筆", "", "", "", ""));
        assertThrows(IllegalArgumentException.class, () -> editor.moveEntry(other, bin));
        assertThrows(IllegalArgumentException.class, () -> editor.addEntry(bin, new EntryDraft("x", "", "", "", "")));
    }

    @Test
    @DisplayName("編輯後的保管庫整份讀回比對一致（SaveCoordinator 的讀回驗證能通過：時間都在秒）")
    void editedTimesAreWholeSeconds() {
        UUID g = editor.addGroup(vault.id(), "群組");
        UUID id = editor.addEntry(g, new EntryDraft("條目", "", "", "", ""));
        editor.updateEntry(id, new EntryDraft("條目2", "", "", "", ""));
        editor.renameGroup(g, "群組2");
        editor.recycleEntry(id);
        for (GroupView group : vault.groups()) {
            if (!group.uuid().equals(vault.id())) {
                assertEquals(0, group.lastModified().getNano(), "群組時間");
            }
        }
        assertEquals(0, entry(vault, id).lastModified().getNano());
    }

    @Test
    @DisplayName("草稿與編輯器都不會在 toString 洩漏內容")
    void draftToStringIsMasked() {
        assertFalse(new EntryDraft("名稱", "帳號", "密碼", "網址", "備註").toString().contains("密碼"));
        assertNotNull(editor.toString());
    }

    private Vault reopen() throws VaultException {
        return codec.open(codec.serialize(vault), TestVaults.password());
    }

    private static EntryView entry(Vault v, UUID id) {
        return v.entries().stream().filter(e -> e.uuid().equals(id)).findFirst().orElseThrow();
    }

    private static GroupView group(Vault v, UUID id) {
        return v.groups().stream().filter(g -> g.uuid().equals(id)).findFirst().orElseThrow();
    }

    private static org.linguafranca.pwdb.PropertyValue property(Vault v, UUID id, String name) {
        return v.database().findEntry(id).getPropertyValue(name);
    }

    private static List<JacksonEntry> history(Vault v, UUID id) {
        return KdbxModelAccess.history(v.database().findEntry(id));
    }
}
