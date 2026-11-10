package app.svault.core.vault;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.linguafranca.pwdb.kdbx.Helpers;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.JacksonHistory;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses.BinaryProperty;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses.StringProperty;
import org.linguafranca.pwdb.kdbx.jackson.model.KeePassFile;
import org.linguafranca.pwdb.kdbx.jackson.model.Times;

/**
 * 讀寫 KeePassJava2 的 Jackson 資料模型中沒有公開 API 的欄位（T-04 API 檢查）：
 * entry History、標籤、群組備註與時間、{@code HistoryMaxItems}、{@code DeletedObjects}、根群組 UUID；
 * T-09 加上寫入用的條目時間、{@code PreviousParentGroup}、歷史版本的複製與 {@code DeletedObjects} 的新增；
 * T-13 加上合併要寫的群組名稱／圖示、標籤、附件參照、整份 {@code DeletedObjects} 與跨保管庫複製用的逐欄複製。
 *
 * <p>這些欄位是 {@code protected}，只能用反射。集中在這一個類別，升級函式庫時欄位改名會讓
 * {@code CompatibilityTest} 失敗。Android 的 R8 已保留 {@code org.linguafranca.**}（STACK §9），欄位名稱不會被縮減。
 */
final class KdbxModelAccess {

    private static final Field DATABASE_FILE = field(JacksonDatabase.class, "keePassFile");
    private static final Field ENTRY_HISTORY = field(JacksonEntry.class, "history");
    private static final Field ENTRY_TAGS = field(JacksonEntry.class, "tags");
    private static final Field GROUP_UUID = field(JacksonGroup.class, "uuid");
    private static final Field GROUP_NOTES = field(JacksonGroup.class, "notes");
    private static final Field GROUP_TIMES = field(JacksonGroup.class, "times");
    private static final Field META_HISTORY_MAX_ITEMS = field(KeePassFile.Meta.class, "historyMaxItems");
    private static final Field ROOT_DELETED_OBJECTS = field(KeePassFile.Root.class, "deletedObject");
    private static final Field ENTRY_TIMES = field(JacksonEntry.class, "times");
    private static final Field ENTRY_STRINGS = field(JacksonEntry.class, "string");
    private static final Field ENTRY_BINARIES = field(JacksonEntry.class, "binary");
    private static final Field ENTRY_PARENT = field(JacksonEntry.class, "parent");
    private static final Field ENTRY_PREVIOUS_PARENT = field(JacksonEntry.class, "previousParentGroup");
    private static final Field GROUP_PREVIOUS_PARENT = field(JacksonGroup.class, "previousParentGroup");
    private static final Field GROUP_NAME = field(JacksonGroup.class, "name");
    private static final Field GROUP_CUSTOM_ICON = field(JacksonGroup.class, "customIconUuid");
    private static final Field GROUP_ICON_ID = field(JacksonGroup.class, "iconID");
    private static final Field ENTRY_ICON_ID = field(JacksonEntry.class, "iconID");

    /** {@code <DeletedObjects>} 在 Jackson 泛用結構裡的鍵（P8）。 */
    private static final String DELETED_OBJECT = "DeletedObject";

    private KdbxModelAccess() {
    }

    static KeePassFile file(JacksonDatabase db) {
        return (KeePassFile) get(DATABASE_FILE, db);
    }

    /** @return 歷史版本數；沒有 History 元素時為 0 */
    static int historyCount(JacksonEntry entry) {
        JacksonHistory history = (JacksonHistory) get(ENTRY_HISTORY, entry);
        return history == null || history.getEntry() == null ? 0 : history.getEntry().size();
    }

    /** @return 歷史版本（最舊的在前）；沒有時為空清單。回傳的是模型內部的清單，只給 core.vault 讀寫 */
    static List<JacksonEntry> history(JacksonEntry entry) {
        JacksonHistory history = (JacksonHistory) get(ENTRY_HISTORY, entry);
        return history == null || history.getEntry() == null ? Collections.emptyList() : history.getEntry();
    }

    static String tags(JacksonEntry entry) {
        Object tags = get(ENTRY_TAGS, entry);
        return tags == null ? "" : (String) tags;
    }

    static String groupNotes(JacksonGroup group) {
        Object notes = get(GROUP_NOTES, group);
        return notes == null ? "" : (String) notes;
    }

    static Times groupTimes(JacksonGroup group) {
        return (Times) get(GROUP_TIMES, group);
    }

    static void setGroupUuid(JacksonGroup group, UUID uuid) {
        try {
            GROUP_UUID.set(group, uuid);
        } catch (IllegalAccessException e) {
            throw incompatible(e);
        }
    }

    static int historyMaxItems(JacksonDatabase db) {
        return (Integer) get(META_HISTORY_MAX_ITEMS, file(db).meta);
    }

    /**
     * @return Jackson 把 {@code <DeletedObjects>} 讀成的泛用結構（Map／List）；沒有刪除紀錄時為 {@code null}
     */
    static Object deletedObjects(JacksonDatabase db) {
        return get(ROOT_DELETED_OBJECTS, file(db).root);
    }

    static Times entryTimes(JacksonEntry entry) {
        return (Times) get(ENTRY_TIMES, entry);
    }

    static UUID previousParent(JacksonEntry entry) {
        return (UUID) get(ENTRY_PREVIOUS_PARENT, entry);
    }

    static void setPreviousParent(JacksonEntry entry, UUID group) {
        set(ENTRY_PREVIOUS_PARENT, entry, group);
    }

    static UUID previousParent(JacksonGroup group) {
        return (UUID) get(GROUP_PREVIOUS_PARENT, group);
    }

    static void setPreviousParent(JacksonGroup group, UUID parent) {
        set(GROUP_PREVIOUS_PARENT, group, parent);
    }

    /**
     * 取得可以寫入的歷史版本清單（沒有 {@code <History>} 時建立一個）。最舊的在前，KeePassXC 也是這個順序。
     */
    static List<JacksonEntry> historyForWrite(JacksonEntry entry) {
        JacksonHistory history = (JacksonHistory) get(ENTRY_HISTORY, entry);
        if (history == null) {
            history = new JacksonHistory();
            set(ENTRY_HISTORY, entry, history);
        }
        if (history.getEntry() == null) {
            history.setEntry(new ArrayList<>());
        }
        return history.getEntry();
    }

    /**
     * 複製條目目前的狀態，作為歷史版本（AC-02-2，P12：函式庫不會自己產生）。
     *
     * <p>逐一複製 {@code JacksonEntry} 宣告的所有欄位，函式庫之後新增的欄位也會被帶過去；之後會被修改的
     * 部分另做深複製：字串欄位（{@code StringProperty.setValue} 會原地修改）、附件參照清單、時間。
     * 欄位值（{@code PropertyValue}）本身不可變，可以共用，受保護的值因此維持受保護。
     * 歷史版本自己不帶 History，也不屬於任何群組（KDBX 規格）。
     */
    static JacksonEntry copyForHistory(JacksonDatabase db, JacksonEntry source) {
        JacksonEntry copy = JacksonEntry.createEntry(db);
        for (Field f : JacksonEntry.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.equals(ENTRY_HISTORY) || f.equals(ENTRY_PARENT)) {
                continue;
            }
            f.setAccessible(true);
            set(f, copy, get(f, source));
        }
        List<StringProperty> strings = new ArrayList<>();
        for (Object o : (List<?>) get(ENTRY_STRINGS, source)) {
            StringProperty p = (StringProperty) o;
            strings.add(new StringProperty(p.getKey(), p.getValue()));
        }
        set(ENTRY_STRINGS, copy, strings);
        set(ENTRY_BINARIES, copy, new ArrayList<>((List<?>) get(ENTRY_BINARIES, source)));
        set(ENTRY_TIMES, copy, copyTimes(entryTimes(source)));
        return copy;
    }

    /**
     * 在 {@code DeletedObjects} 後面加上紀錄（永久刪除；P8 的寫入）。沿用 Jackson 讀入時的泛用結構
     * （{@code {DeletedObject=[{UUID=…, DeletionTime=…}…]}}），原有的紀錄原樣保留。時間用 KDBX 4 的格式
     * （自 0001-01-01 起的秒數，Base64），和函式庫讀入的字串一致。
     */
    static void addDeletedObjects(JacksonDatabase db, List<UUID> uuids, Date time) {
        KeePassFile.Root root = file(db).root;
        Object raw = get(ROOT_DELETED_OBJECTS, root);
        List<Object> items = new ArrayList<>();
        if (raw instanceof Map) {
            Object existing = ((Map<?, ?>) raw).get(DELETED_OBJECT);
            if (existing instanceof Collection) {
                items.addAll((Collection<?>) existing);
            } else if (existing != null) {
                items.add(existing);
            }
        }
        String when = Helpers.fromDateV4(time);
        for (UUID uuid : uuids) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("UUID", Helpers.base64FromUuid(uuid));
            item.put("DeletionTime", when);
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(DELETED_OBJECT, items);
        set(ROOT_DELETED_OBJECTS, root, out);
    }

    /**
     * 以整份清單取代 {@code DeletedObjects}（三方合併的聯集，T-13）。格式同 {@link #addDeletedObjects}；
     * 清單是空的時移除整個元素（和沒有刪除紀錄的檔案一樣）。
     */
    static void setDeletedObjects(JacksonDatabase db, Map<UUID, Date> deleted) {
        KeePassFile.Root root = file(db).root;
        if (deleted.isEmpty()) {
            set(ROOT_DELETED_OBJECTS, root, null);
            return;
        }
        List<Object> items = new ArrayList<>();
        for (Map.Entry<UUID, Date> d : deleted.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("UUID", Helpers.base64FromUuid(d.getKey()));
            item.put("DeletionTime", Helpers.fromDateV4(d.getValue()));
            items.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(DELETED_OBJECT, items);
        set(ROOT_DELETED_OBJECTS, root, out);
    }

    /** 改群組名稱，不經過會 touch（改成帶毫秒的當下時間）的 {@code setName}。 */
    static void setGroupName(JacksonGroup group, String name) {
        set(GROUP_NAME, group, name);
    }

    static void setGroupNotes(JacksonGroup group, String notes) {
        set(GROUP_NOTES, group, notes);
    }

    static int groupIconId(JacksonGroup group) {
        return (Integer) get(GROUP_ICON_ID, group);
    }

    static UUID groupCustomIcon(JacksonGroup group) {
        return (UUID) get(GROUP_CUSTOM_ICON, group);
    }

    /** 設定圖示，不 touch。 */
    static void setGroupIcon(JacksonGroup group, int iconId, UUID customIcon) {
        set(GROUP_ICON_ID, group, iconId);
        set(GROUP_CUSTOM_ICON, group, customIcon);
    }

    static void setEntryIcon(JacksonEntry entry, int iconId, UUID customIcon) {
        set(ENTRY_ICON_ID, entry, iconId);
        entry.setCustomIconUUID(customIcon);
    }

    static int entryIconId(JacksonEntry entry) {
        return (Integer) get(ENTRY_ICON_ID, entry);
    }

    static void setTags(JacksonEntry entry, String tags) {
        set(ENTRY_TAGS, entry, tags == null || tags.isEmpty() ? null : tags);
    }

    /** @return 條目的字串欄位清單（模型內部的清單，只給 core.vault 讀寫） */
    @SuppressWarnings("unchecked")
    static List<StringProperty> strings(JacksonEntry entry) {
        return (List<StringProperty>) get(ENTRY_STRINGS, entry);
    }

    /** @return 條目的附件參照清單（模型內部的清單；歷史版本也有，但歷史版本沒有 {@code database}） */
    @SuppressWarnings("unchecked")
    static List<BinaryProperty> binaries(JacksonEntry entry) {
        return (List<BinaryProperty>) get(ENTRY_BINARIES, entry);
    }

    /** 換掉整個附件參照清單（跨保管庫複製時重建參照）。 */
    static void setBinaries(JacksonEntry entry, List<BinaryProperty> list) {
        set(ENTRY_BINARIES, entry, list);
    }

    static void setEntryTimes(JacksonEntry entry, Times times) {
        set(ENTRY_TIMES, entry, times);
    }

    static void setGroupTimes(JacksonGroup group, Times times) {
        set(GROUP_TIMES, group, times);
    }

    /**
     * 把 {@code source} 宣告的欄位逐一複製到 {@code target}（淺複製），{@code skip} 列出的欄位與靜態欄位除外。
     * 跨保管庫複製（{@link KdbxCopy}）用：函式庫之後新增的欄位也會被帶過去，S-Vault 看不懂的內容因此保留（AC-01-2）。
     */
    static <T> void copyDeclaredFields(Class<T> type, T source, T target, List<String> skip) {
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || skip.contains(f.getName())) {
                continue;
            }
            f.setAccessible(true);
            set(f, target, get(f, source));
        }
    }

    static Times copyTimes(Times t) {
        Times copy = new Times(t.getCreationTime());
        copy.setLastModificationTime(t.getLastModificationTime());
        copy.setLastAccessTime(t.getLastAccessTime());
        copy.setExpiryTime(t.getExpiryTime());
        copy.setExpires(t.getExpires());
        copy.setUsageCount(t.getUsageCount());
        copy.setLocationChanged(t.getLocationChanged());
        return copy;
    }

    private static void set(Field field, Object target, Object value) {
        try {
            field.set(target, value);
        } catch (IllegalAccessException e) {
            throw incompatible(e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException | RuntimeException e) {
            throw incompatible(e);
        }
    }

    private static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw incompatible(e);
        }
    }

    private static IllegalStateException incompatible(Exception e) {
        return new IllegalStateException("KeePassJava2 的資料模型與 T-04 檢查時不同（升級函式庫後要重新檢查）："
                + e.getClass().getSimpleName());
    }
}
