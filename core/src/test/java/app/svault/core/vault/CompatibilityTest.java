package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

/**
 * KeePassXC 產生的測試檔（SETUP S-TD1）能讀取，S-Vault 修改並存檔後再讀回，內容除了被改的欄位外完全相同
 * （AC-01-2、AC-15-3）。測試檔使用 KeePassXC 的正式 KDF 參數（64 MiB／2 次），每個檔案約 1 秒。
 */
class CompatibilityTest {

    static final List<String> FIXTURES = TestVaults.READABLE_KXC_FIXTURES;

    private final VaultCodec codec = new VaultCodec();

    @ParameterizedTest(name = "{0}")
    @FieldSource("FIXTURES")
    @DisplayName("AC-15-3、AC-01-2：讀取 → 修改 → 存檔 → 讀回，函式庫讀到的一切（含附件、歷史、刪除紀錄）都保留")
    void roundTripPreservesEverything(String fixture) throws Exception {
        String expectedXml;
        byte[] saved;
        List<EntryView> before;
        try (Vault vault = codec.open(TestVaults.fixture(fixture), TestVaults.password())) {
            TestEntries.touchFirstEntryNotes(vault, "S-Vault 修改過");
            before = vault.entries();
            expectedXml = TestEntries.plainXml(vault);
            saved = codec.serialize(vault);
        }
        try (Vault reopened = codec.open(saved, TestVaults.password())) {
            assertEquals(expectedXml, TestEntries.plainXml(reopened));
            assertEquals(byUuid(before), byUuid(reopened.entries()));
            assertTrue(reopened.entries().stream().anyMatch(e -> e.notes().equals("S-Vault 修改過")));
        }
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("FIXTURES")
    @DisplayName("AC-15-3：開啟 KeePassXC 檔案時沿用檔案自己的加密設定，存檔後不變")
    void keepsFileFormat(String fixture) throws Exception {
        try (Vault vault = codec.open(TestVaults.fixture(fixture), TestVaults.password())) {
            VaultFormat format = vault.format();
            String cipher = fixture.equals(TestVaults.KXC_ARGON2D_CHACHA20) ? "ChaCha20" : "AES-256";
            assertEquals(new VaultFormat(4, cipher, "Argon2d", 64L * 1024 * 1024, 2, 4, "ChaCha20"), format);
            try (Vault reopened = codec.open(codec.serialize(vault), TestVaults.password())) {
                assertEquals(format, reopened.format());
            }
        }
    }

    @Test
    @DisplayName("AC-01-2：中文檔名的附件與自訂欄位讀得到")
    void attachmentsAndCustomFields() throws Exception {
        try (Vault vault = codec.open(TestVaults.fixture(TestVaults.KXC_ATTACHMENTS_FIELDS), TestVaults.password())) {
            List<EntryView> entries = vault.entries();
            assertEquals(2, entries.stream().mapToInt(e -> e.attachmentNames().size()).sum());
            assertTrue(entries.stream().flatMap(e -> e.attachmentNames().stream())
                    .allMatch(name -> name.chars().anyMatch(c -> c > 127)), "附件檔名應含中文");
            assertEquals(2, entries.stream().filter(e -> e.customFields().size() == 2).count());
            for (EntryView e : entries) {
                List<String> names = new java.util.ArrayList<>(e.customFields().keySet());
                assertEquals(names.stream().sorted().collect(Collectors.toList()), names, "自訂欄位依名稱排序");
            }
        }
    }

    @Test
    @DisplayName("AC-15-3：3 層群組、資源回收桶與歷史版本")
    void groupsRecycleBinAndHistory() throws Exception {
        try (Vault vault = codec.open(TestVaults.fixture(TestVaults.KXC_GROUPS_RECYCLE_HISTORY),
                TestVaults.password())) {
            List<GroupView> groups = vault.groups();
            assertEquals(vault.id(), groups.get(0).uuid());
            Map<java.util.UUID, GroupView> byId = groups.stream()
                    .collect(Collectors.toMap(GroupView::uuid, Function.identity()));
            int maxDepth = groups.stream().mapToInt(g -> depth(g, byId)).max().orElse(0);
            assertEquals(3, maxDepth, "根群組＋2 層子群組");
            GroupView recycle = groups.stream().filter(GroupView::recycleBin).findFirst()
                    .orElseThrow(AssertionError::new);
            assertEquals(1, vault.entries().stream().filter(e -> e.groupId().equals(recycle.uuid())).count());
            assertTrue(vault.entries().stream().anyMatch(e -> e.historyCount() == 3));
            assertEquals(10, vault.historyMaxItems());
            assertNotEquals(vault.id(), recycle.uuid());
        }
    }

    @Test
    @DisplayName("KeePassXC 的 DeletedObjects 讀得到，存檔後仍是 2 筆")
    void deletedObjects() throws Exception {
        try (Vault vault = codec.open(TestVaults.fixture(TestVaults.KXC_DELETED_OBJECTS), TestVaults.password())) {
            List<DeletedObjectView> deleted = vault.deletedObjects();
            assertEquals(2, deleted.size());
            assertTrue(deleted.stream().noneMatch(d -> d.uuid() == null || d.deletionTime() == null));
            try (Vault reopened = codec.open(codec.serialize(vault), TestVaults.password())) {
                assertEquals(deleted, reopened.deletedObjects());
            }
        }
    }

    private static int depth(GroupView group, Map<java.util.UUID, GroupView> byId) {
        int depth = 1;
        GroupView current = group;
        while (current.parentId() != null) {
            current = byId.get(current.parentId());
            depth++;
        }
        return depth;
    }

    private static Map<java.util.UUID, EntryView> byUuid(List<EntryView> entries) {
        return entries.stream().collect(Collectors.toMap(EntryView::uuid, Function.identity()));
    }
}
