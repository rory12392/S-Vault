package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.session.TestSessions;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.sync.MergeResult;
import app.svault.core.sync.SyncReport;
import app.svault.core.sync.ThreeWayMerger;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linguafranca.pwdb.PropertyValue;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses.StringProperty;

/**
 * T-29（AC-07-7、D-101 修訂）：只為合併暫時開啟的基準不經 {@code SealedStore} 封存密碼
 * （{@link VaultCodec#openForMerge}），但內容、保護旗標與快照都和一般開啟相同；從基準複製到要寫出的那一份時重新封存，
 * 不封存的值不會離開基準。只用假資料。
 */
class MergeBaseUnsealedTest {

    private static final String PIN = "PIN";

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-27T08:00:00Z"), ZoneOffset.UTC);
    private Vault key;
    private UUID shared;
    private byte[] bytes;

    @BeforeEach
    void setUp() throws Exception {
        key = codec.open(TestVaults.newVaultBytes("家用"), TestVaults.password());
        VaultEditor editor = new VaultEditor(key, clock);
        shared = editor.addEntry(key.id(), new EntryDraft("銀行", "rory", "base-pw", "https://bank.example", ""));
        TestVaults.setField(key, shared, PIN, "0000", true);
        TestVaults.setField(key, shared, "Hint", "原本", false);
        clock.advance(Duration.ofMinutes(1));
        editor.updateEntry(shared, new EntryDraft("銀行", "rory", "base-pw-2", "https://bank.example", ""));
        bytes = codec.serialize(key);
    }

    @Test
    @DisplayName("openForMerge：受保護的欄位不封存但仍標為受保護，快照（內容與保護旗標）和一般開啟完全相同")
    void unsealedBaseHasSameSnapshot() throws Exception {
        try (Vault sealed = codec.openWithKeyOf(bytes, key);
             Vault unsealed = codec.openForMerge(bytes, key)) {
            assertEquals(sealed.snapshot().entries(), unsealed.snapshot().entries());
            assertEquals(sealed.snapshot().groups(), unsealed.snapshot().groups());

            List<PropertyValue> values = protectedValues(unsealed);
            assertFalse(values.isEmpty());
            for (PropertyValue v : values) {
                assertTrue(v.isProtected());
                assertFalse(v instanceof PropertyValue.SealedStore, "基準不封存：" + v.getClass().getSimpleName());
                assertFalse(String.valueOf(v).contains("base-pw"), "toString 不含明文");
            }
            for (PropertyValue v : protectedValues(sealed)) {
                assertInstanceOf(PropertyValue.SealedStore.class, v, "一般開啟仍封存（D-101 的範圍不變）");
            }
        }
    }

    @Test
    @DisplayName("從不封存的一份套用到要寫出的一份（改欄位、新條目含歷史）時一律重新封存")
    void valuesCopiedFromUnsealedAreResealed() throws Exception {
        UUID added;
        byte[] mineBytes;
        try (Vault mine = codec.openWithKeyOf(bytes, key)) {
            VaultEditor editor = new VaultEditor(mine, clock);
            clock.advance(Duration.ofMinutes(1));
            editor.updateEntry(shared, new EntryDraft("銀行", "rory", "mine-pw", "https://bank.example", ""));
            TestVaults.setField(mine, shared, PIN, "1111", true);
            added = editor.addEntry(mine.id(), new EntryDraft("信箱", "r", "new-pw", "", ""));
            clock.advance(Duration.ofMinutes(1));
            editor.updateEntry(added, new EntryDraft("信箱", "r", "new-pw-2", "", ""));
            mineBytes = codec.serialize(mine);
        }

        try (Vault base = codec.openWithKeyOf(bytes, key);
             Vault mine = codec.openForMerge(mineBytes, key);
             Vault remote = codec.openWithKeyOf(bytes, key)) {
            MergeResult result = ThreeWayMerger.merge(base.snapshot(), mine.snapshot(), remote.snapshot());
            assertFalse(result.hasConflicts());

            MergeWriter.apply(remote, mine, result.plan());

            assertEquals(TestVaults.content(mine), TestVaults.content(remote), "合併結果和來源相同");
            List<PropertyValue> values = protectedValues(remote);
            assertTrue(values.size() >= 5, "密碼、PIN 與新條目（含歷史）都檢查到：" + values.size());
            for (PropertyValue v : values) {
                assertInstanceOf(PropertyValue.SealedStore.class, v, "寫出的那一份一律封存");
            }
            assertEquals("mine-pw", password(remote, shared));
            assertEquals("new-pw-2", password(remote, added));
        }
    }

    private static String password(Vault vault, UUID id) {
        return vault.entries().stream().filter(e -> e.uuid().equals(id)).findFirst().orElseThrow().password();
    }

    @Test
    @DisplayName("SyncService：這台沒修改、遠端被整份取代時，從基準（不封存）補回的條目在開啟中的保管庫裡是封存的")
    void syncKeepsOpenVaultSealed(@TempDir Path tmp) throws Exception {
        MemoryLocation remote = new MemoryLocation("H:/S-Vault/t29.kdbx", bytes);
        VaultSession phone = TestSessions.assemble(codec, LocalStore.open(tmp.resolve("phone"), k -> null, k -> null),
                clock).session();
        try {
            phone.unlock(remote, TestVaults.password());
            clock.advance(Duration.ofMinutes(1));
            UUID added = new VaultEditor(phone.vault(), clock).addEntry(phone.vault().id(),
                    new EntryDraft("手機新增", "m", "phone-pw", "", ""));
            phone.save();
            phone.lock();
            remote.replace(bytes);

            phone.unlock(remote, TestVaults.password());
            SyncReport report = phone.refresh();

            assertEquals(SyncReport.Status.MERGED, report.status());
            assertEquals("phone-pw", password(phone.vault(), added), "基準裡的條目補回來了");
            for (PropertyValue v : protectedValues(phone.vault())) {
                assertInstanceOf(PropertyValue.SealedStore.class, v, "開啟中的保管庫一律封存");
            }
        } finally {
            phone.lock();
        }
    }

    @Test
    @DisplayName("openForMerge 和 openWithKeyOf 一樣：上鎖的金鑰來源丟例外，壞檔是 CorruptVaultException")
    void openForMergeKeepsErrorContract() throws Exception {
        assertThrows(CorruptVaultException.class, () -> codec.openForMerge(new byte[] {1, 2, 3}, key));
        key.close();
        assertThrows(IllegalStateException.class, () -> codec.openForMerge(bytes, key));
    }

    private static List<PropertyValue> protectedValues(Vault vault) {
        List<PropertyValue> out = new ArrayList<>();
        collect(vault.database().getRootGroup(), out);
        return out;
    }

    private static void collect(JacksonGroup group, List<PropertyValue> out) {
        for (JacksonEntry e : group.getEntries()) {
            addProtected(e, out);
            for (JacksonEntry h : KdbxModelAccess.history(e)) {
                addProtected(h, out);
            }
        }
        for (JacksonGroup child : group.getGroups()) {
            collect(child, out);
        }
    }

    private static void addProtected(JacksonEntry e, List<PropertyValue> out) {
        for (StringProperty p : KdbxModelAccess.strings(e)) {
            if (p.getValue() != null && p.getValue().isProtected()) {
                out.add(p.getValue());
            }
        }
    }
}
