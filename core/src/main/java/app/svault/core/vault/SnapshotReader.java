package app.svault.core.vault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses.BinaryProperty;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses.StringProperty;
import org.linguafranca.pwdb.kdbx.jackson.model.Times;

/**
 * 由開啟中的保管庫建立 {@link VaultSnapshot}（{@link Vault#snapshot()}）。欄位的攤平方式見 {@link EntrySnapshot}。
 */
final class SnapshotReader {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private SnapshotReader() {
    }

    static VaultSnapshot read(Vault vault) {
        JacksonDatabase db = vault.database();
        JacksonGroup root = db.getRootGroup();
        Map<UUID, GroupSnapshot> groups = new LinkedHashMap<>();
        Map<UUID, EntrySnapshot> entries = new LinkedHashMap<>();
        walk(db, root, null, groups, entries);
        Map<UUID, Instant> deleted = new LinkedHashMap<>();
        for (DeletedObjectView d : vault.deletedObjects()) {
            Instant before = deleted.get(d.uuid());
            if (before == null || d.deletionTime().isAfter(before)) {
                deleted.put(d.uuid(), d.deletionTime());
            }
        }
        UUID bin = KdbxModelAccess.file(db).meta.recycleBinUUID;
        return new VaultSnapshot(root.getUuid(), entries, groups, deleted,
                bin != null && groups.containsKey(bin) ? bin : null);
    }

    private static void walk(JacksonDatabase db, JacksonGroup group, UUID parent, Map<UUID, GroupSnapshot> groups,
                             Map<UUID, EntrySnapshot> entries) {
        Map<String, String> fields = new HashMap<>();
        fields.put(GroupSnapshot.NAME, nonNull(group.getName()));
        fields.put(GroupSnapshot.NOTES, KdbxModelAccess.groupNotes(group));
        fields.put(GroupSnapshot.ICON, icon(KdbxModelAccess.groupIconId(group), KdbxModelAccess.groupCustomIcon(group)));
        Times times = KdbxModelAccess.groupTimes(group);
        groups.put(group.getUuid(), new GroupSnapshot(group.getUuid(), parent, fields,
                instant(times == null ? null : times.getLastModificationTime()),
                instant(times == null ? null : times.getLocationChanged())));
        for (JacksonEntry e : group.getEntries()) {
            entries.put(e.getUuid(), entry(db, e, group.getUuid()));
        }
        for (JacksonGroup child : group.getGroups()) {
            walk(db, child, group.getUuid(), groups, entries);
        }
    }

    private static EntrySnapshot entry(JacksonDatabase db, JacksonEntry e, UUID groupId) {
        Map<String, String> fields = new HashMap<>();
        Set<String> protectedKeys = new HashSet<>();
        for (StringProperty p : KdbxModelAccess.strings(e)) {
            String value = p.getValue() == null ? "" : p.getValue().getValueAsString();
            fields.put(EntrySnapshot.stringKey(p.getKey()), value == null ? "" : value);
            if (p.getValue() != null && p.getValue().isProtected()) {
                protectedKeys.add(EntrySnapshot.stringKey(p.getKey()));
            }
        }
        for (BinaryProperty p : KdbxModelAccess.binaries(e)) {
            byte[] content = KdbxCopy.binary(db, e, p.getKey());
            fields.put(EntrySnapshot.attachmentKey(p.getKey()), content == null ? "missing" : sha256(content));
        }
        String tags = KdbxModelAccess.tags(e);
        if (!tags.isEmpty()) {
            fields.put(EntrySnapshot.TAGS, tags);
        }
        fields.put(EntrySnapshot.ICON, icon(KdbxModelAccess.entryIconId(e), e.getCustomIconUuid()));
        Times times = KdbxModelAccess.entryTimes(e);
        fields.put(EntrySnapshot.EXPIRY, expiry(times));
        List<Instant> history = new ArrayList<>();
        for (JacksonEntry h : KdbxModelAccess.history(e)) {
            history.add(instant(h.getLastModificationTime()));
        }
        return new EntrySnapshot(e.getUuid(), groupId, fields, protectedKeys, instant(times.getLastModificationTime()),
                instant(times.getLocationChanged()), history);
    }

    /**
     * 讀回驗證用的內容摘要（C-3）：每個條目與它的每個歷史版本的全部欄位、附件內容的 SHA-256、保護旗標、標籤、圖示、
     * 到期設定與修改時間（只到秒，KDBX 4 的精度），依條目 UUID 排序後算 SHA-256。群組、目前版本的時間與刪除紀錄
     * 由 {@code ReadBack} 另外比對。只回傳雜湊，不帶任何內容。刻意不比對建立時間、最後存取時間與使用次數（不是使用者的內容，
     * 其他工具寫的值和精度可能不同，比了反而誤判讀回失敗；Codex M-04 審查待辦 4）。
     */
    static String contentDigest(Vault vault) {
        JacksonDatabase db = vault.database();
        List<JacksonEntry> all = new ArrayList<>();
        collect(db.getRootGroup(), all);
        all.sort((a, b) -> a.getUuid().toString().compareTo(b.getUuid().toString()));
        StringBuilder canonical = new StringBuilder();
        for (JacksonEntry e : all) {
            canonical.append("E\u0000").append(e.getUuid()).append('\u0000');
            appendVersion(canonical, db, e, e.getUuid());
            for (JacksonEntry h : KdbxModelAccess.history(e)) {
                canonical.append("H\u0000");
                appendVersion(canonical, db, h, e.getUuid());
                canonical.append(instant(h.getLastModificationTime()).getEpochSecond()).append('\u0000');
            }
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void collect(JacksonGroup group, List<JacksonEntry> out) {
        out.addAll(group.getEntries());
        for (JacksonGroup child : group.getGroups()) {
            collect(child, out);
        }
    }

    private static void appendVersion(StringBuilder out, JacksonDatabase db, JacksonEntry version, UUID groupKey) {
        EntrySnapshot s = entry(db, version, groupKey);
        out.append('F').append(s.fields().size()).append(';');
        new TreeMap<>(s.fields()).forEach((k, v) -> {
            field(out, k);
            field(out, v);
        });
        out.append('P').append(s.protectedKeys().size()).append(';');
        new TreeSet<>(s.protectedKeys()).forEach(k -> field(out, k));
    }

    /**
     * 帶長度的編碼（Codex M-04 審查 B2）：只用分隔字元時，名稱「a」值「b=c」和名稱「a=b」值「c」會編成同一串，
     * 內容不同卻算出同一個摘要。長度在前，任何內容都只有一種讀法。
     */
    static void field(StringBuilder out, String s) {
        out.append(s.length()).append(':').append(s);
    }

    /** 不到期時不比較到期時間：KeePassXC 不到期的條目也會寫一個到期時間，兩邊不同不代表使用者改過。 */
    private static String expiry(Times times) {
        if (!Boolean.TRUE.equals(times.getExpires()) || times.getExpiryTime() == null) {
            return "off";
        }
        return "on:" + times.getExpiryTime().toInstant().getEpochSecond();
    }

    private static String icon(int id, UUID custom) {
        return custom == null ? String.valueOf(id) : id + "/" + custom;
    }

    private static String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            char[] out = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                out[i * 2] = HEX[(digest[i] >> 4) & 0xf];
                out[i * 2 + 1] = HEX[digest[i] & 0xf];
            }
            return new String(out);
        } catch (NoSuchAlgorithmException e) {
            // 每個 Java 平台都必須提供 SHA-256
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }

    private static Instant instant(Date date) {
        return date == null ? Instant.EPOCH : date.toInstant();
    }
}
