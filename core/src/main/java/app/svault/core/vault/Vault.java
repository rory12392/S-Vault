package app.svault.core.vault;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.kdbx.Helpers;
import org.linguafranca.pwdb.kdbx.KdbxHeader;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.model.Times;
import org.linguafranca.pwdb.security.Encryption;
import org.linguafranca.pwdb.security.VariantDictionary;

/**
 * 開啟中的保管庫（Architecture §6）。由 {@link VaultCodec} 建立；內容以唯讀快照（{@link EntryView}、
 * {@link GroupView}）提供，寫入在 T-09 的 {@code VaultEditor}。
 *
 * <p>持有解鎖期間需要的金鑰材料（複合金鑰與已衍生的金鑰），讓存檔與讀回驗證不必再要主密碼、也不必重算 KDF。
 * {@link #close()}（上鎖）時清零並丟棄資料庫物件。不是執行緒安全的：同一時間只由一個背景執行緒使用
 * （CONVENTIONS §2）。
 */
public final class Vault implements AutoCloseable {

    private static final List<String> STANDARD_FIELDS = Entry.STANDARD_PROPERTY_NAMES;

    private JacksonDatabase db;
    private final CachedKeyHeader header;
    private final KeyMaterial key;

    Vault(JacksonDatabase db, CachedKeyHeader header, KeyMaterial key) {
        this.db = db;
        this.header = header;
        this.key = key;
    }

    /**
     * @return 保管庫 ID＝根群組 UUID（Architecture §2.1，用來命名本機資料）
     */
    public UUID id() {
        return database().getRootGroup().getUuid();
    }

    /**
     * @return 資料庫名稱；沒有時為空字串
     */
    public String name() {
        String name = database().getName();
        return name == null ? "" : name;
    }

    /**
     * @return 檔案的加密設定（開啟時讀到的；建立時是 AC-01-8 的參數）
     */
    public VaultFormat format() {
        checkOpen();
        UUID kdf = header.getKdfParameters().mustGet("$UUID").asUuid();
        String cipher = KdbxHeaderScan.CIPHER_CHACHA20.equals(header.getCipherUuid()) ? "ChaCha20" : "AES-256";
        String inner = header.getProtectedStreamAlgorithm() == Encryption.ProtectedStreamAlgorithm.CHA_CHA_20
                ? "ChaCha20" : "Salsa20";
        if (KdbxHeaderScan.KDF_ARGON2D.equals(kdf)) {
            return new VaultFormat(header.getVersion(), cipher, "Argon2d",
                    header.getKdfParameters().mustGet("M").asLong(),
                    header.getKdfParameters().mustGet("I").asLong(),
                    header.getKdfParameters().mustGet("P").asInteger(), inner);
        }
        return new VaultFormat(header.getVersion(), cipher, "AES-KDF", 0,
                header.getKdfParameters().mustGet("R").asLong(), 0, inner);
    }

    /**
     * 換成新的金鑰衍生參數與新的鹽（L-4 的升級，D-187）。只改記憶體中的標頭：下次序列化時用保留的複合金鑰重算一次
     * KDF（{@link CachedKeyHeader}），不需要主密碼；外層加密法不變。
     *
     * @param params Argon2d 參數（通常是 {@link KdfParams#DEFAULT}）
     * @throws IllegalStateException 已上鎖
     */
    public void useKdf(KdfParams params) {
        checkOpen();
        // 新的 KDBX 4 標頭預設就是 Argon2d，並帶一組隨機的鹽
        VariantDictionary kdf = new KdbxHeader(4).getKdfParameters();
        kdf.putULong("M", params.memoryBytes());
        kdf.putULong("I", params.iterations());
        kdf.putUInt("P", params.parallelism());
        header.setKdfParameters(kdf);
    }

    /**
     * @return 所有群組，根群組在第一個，之後依樹的前序（含資源回收桶）
     */
    public List<GroupView> groups() {
        UUID recycleBin = KdbxModelAccess.file(database()).meta.recycleBinUUID;
        List<GroupView> out = new ArrayList<>();
        collectGroups(db.getRootGroup(), null, recycleBin, out);
        return Collections.unmodifiableList(out);
    }

    /**
     * @return 所有條目的快照（含資源回收桶裡的；不含歷史版本），依群組前序與檔案中的順序
     */
    public List<EntryView> entries() {
        List<EntryView> out = new ArrayList<>();
        collectEntries(database().getRootGroup(), out);
        return Collections.unmodifiableList(out);
    }

    /**
     * @return KDBX 的 {@code DeletedObjects}，依檔案中的順序
     */
    public List<DeletedObjectView> deletedObjects() {
        Object raw = KdbxModelAccess.deletedObjects(database());
        List<DeletedObjectView> out = new ArrayList<>();
        if (raw instanceof Map) {
            // Jackson 把 <DeletedObjects> 讀成 {DeletedObject=[{UUID=…, DeletionTime=…}, …]}；只有一筆時不是 List
            Object items = ((Map<?, ?>) raw).get("DeletedObject");
            Collection<?> list = items instanceof Collection ? (Collection<?>) items
                    : items == null ? Collections.emptyList() : Collections.singletonList(items);
            for (Object item : list) {
                if (item instanceof Map) {
                    Map<?, ?> fields = (Map<?, ?>) item;
                    Object uuid = fields.get("UUID");
                    Object time = fields.get("DeletionTime");
                    if (uuid instanceof String && time instanceof String) {
                        out.add(new DeletedObjectView(Helpers.uuidFromBase64((String) uuid),
                                Helpers.toDate((String) time).toInstant()));
                    }
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 條目的歷史版本（KDBX 內建的 entry History；T-13 的合併測試與之後的衝突畫面用）。
     *
     * @param entryId 條目（含資源回收桶裡的）
     * @return 歷史版本的快照，最舊的在前；{@code groupId} 是條目目前的群組，{@code historyCount} 為 0
     * @throws IllegalArgumentException 找不到條目
     * @throws IllegalStateException    保管庫已上鎖
     */
    public List<EntryView> history(UUID entryId) {
        JacksonEntry entry = VaultTree.findEntry(database().getRootGroup(), entryId);
        if (entry == null) {
            throw new IllegalArgumentException("找不到條目：" + entryId);
        }
        List<EntryView> out = new ArrayList<>();
        for (JacksonEntry h : KdbxModelAccess.history(entry)) {
            out.add(view(h, entry.getParent().getUuid()));
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 三方合併的輸入（T-13）：全部條目、群組與刪除紀錄的不可變快照。
     *
     * @return 目前內容的快照
     * @throws IllegalStateException 保管庫已上鎖
     */
    public VaultSnapshot snapshot() {
        return SnapshotReader.read(this);
    }

    /**
     * 讀回驗證用（C-3）：所有條目與歷史版本內容的 SHA-256（含附件內容、保護旗標）。只是雜湊，不含任何明文。
     *
     * @return 十六進位的摘要
     * @throws IllegalStateException 保管庫已上鎖
     */
    public String contentDigest() {
        return SnapshotReader.contentDigest(this);
    }

    /**
     * @return 每筆條目保留的歷史版本上限（檔案的 {@code HistoryMaxItems}；AC-02-2，T-09 使用）
     */
    public int historyMaxItems() {
        return KdbxModelAccess.historyMaxItems(database());
    }

    /**
     * @return 已上鎖（{@link #close()} 過）
     */
    public boolean isClosed() {
        return db == null;
    }

    /**
     * 上鎖：清零金鑰材料、丟棄資料庫物件。之後呼叫任何方法都會拋 {@link IllegalStateException}。
     * 重複呼叫沒有作用。
     */
    @Override
    public void close() {
        if (db != null) {
            key.wipe();
            db = null;
        }
    }

    JacksonDatabase database() {
        checkOpen();
        return db;
    }

    CachedKeyHeader header() {
        checkOpen();
        return header;
    }

    KeyMaterial key() {
        checkOpen();
        return key;
    }

    /**
     * 測試用（testFixtures 的 {@code TestVaults.setField}，函式庫的型別不在它的編譯路徑上）：直接設定條目的一個字串
     * 欄位，可指定受保護。S-Vault 的編輯畫面沒有自訂欄位，T-16 用它做「受保護的自訂欄位」的衝突。不改修改時間。
     */
    void setField(UUID entryId, String name, String value, boolean isProtected) {
        JacksonDatabase d = database();
        d.findEntry(entryId).setPropertyValue(name, (isProtected ? d.getPropertyValueStrategy().newProtected()
                : d.getPropertyValueStrategy().newUnprotected()).of(value));
    }

    /** @return 這個保管庫自開啟以來實際執行 KDF 的次數（測試用） */
    int keyDerivations() {
        return header.derivations();
    }

    private void checkOpen() {
        if (db == null) {
            throw new IllegalStateException("保管庫已上鎖");
        }
    }

    private static void collectGroups(JacksonGroup group, UUID parent, UUID recycleBin, List<GroupView> out) {
        Times times = KdbxModelAccess.groupTimes(group);
        out.add(new GroupView(group.getUuid(), parent, nonNull(group.getName()), KdbxModelAccess.groupNotes(group),
                group.getUuid().equals(recycleBin), instant(times == null ? null : times.getLastModificationTime())));
        for (JacksonGroup child : group.getGroups()) {
            collectGroups(child, group.getUuid(), recycleBin, out);
        }
    }

    private static void collectEntries(JacksonGroup group, List<EntryView> out) {
        for (JacksonEntry entry : group.getEntries()) {
            out.add(view(entry, group.getUuid()));
        }
        for (JacksonGroup child : group.getGroups()) {
            collectEntries(child, out);
        }
    }

    private static EntryView view(JacksonEntry e, UUID groupId) {
        Map<String, String> custom = new HashMap<>();
        for (String name : e.getPropertyNames()) {
            if (!STANDARD_FIELDS.contains(name)) {
                custom.put(name, nonNull(e.getProperty(name)));
            }
        }
        return new EntryView(e.getUuid(), groupId,
                nonNull(e.getTitle()), nonNull(e.getUsername()), nonNull(e.getPassword()), nonNull(e.getUrl()),
                nonNull(e.getNotes()), custom, e.getBinaryPropertyNames(), KdbxModelAccess.tags(e),
                instant(e.getCreationTime()), instant(e.getLastModificationTime()), KdbxModelAccess.historyCount(e));
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static Instant instant(Date date) {
        return date == null ? Instant.EPOCH : date.toInstant();
    }
}
