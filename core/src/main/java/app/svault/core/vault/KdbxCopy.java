package app.svault.core.vault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.linguafranca.pwdb.PropertyValue;
import org.linguafranca.pwdb.kdbx.Helpers;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses.BinaryProperty;
import org.linguafranca.pwdb.kdbx.jackson.model.EntryClasses.StringProperty;
import org.linguafranca.pwdb.kdbx.jackson.model.KeePassFile;

/**
 * 把條目與群組從一個保管庫複製到另一個（三方合併把本機的修改套用到遠端那份資料庫物件，Architecture §4 第 7 點）。
 *
 * <p>同一個保管庫裡複製（{@link KdbxModelAccess#copyForHistory}）可以共用附件參照；跨保管庫不行：附件內容存在各自的
 * 二進位資料區（{@code Meta/Binaries}），參照的是編號。這裡讀出來源的位元組、放進目標的資料區、重建參照。
 * 歷史版本載入後沒有 {@code database}（函式庫只替群組裡的條目補上），所以不用函式庫的 {@code getBinaryProperty}，
 * 一律由保管庫自己的資料區查。自訂圖示也存在保管庫層級（{@code Meta/CustomIcons}），目標沒有時一併複製。
 */
final class KdbxCopy {

    /** 跨保管庫複製時不直接沿用的條目欄位：另外處理（深複製、重建）或不屬於這個條目。 */
    private static final List<String> ENTRY_SKIP =
            Arrays.asList("database", "parent", "history", "string", "binary", "times");
    /** 群組只複製本身，不帶子項目（子項目由合併各自決定）。 */
    private static final List<String> GROUP_SKIP =
            Arrays.asList("database", "parent", "entries", "groups", "times");

    private KdbxCopy() {
    }

    /**
     * 複製條目（含或不含歷史版本）到 {@code target}。回傳的條目還不屬於任何群組；時間與來源相同。
     *
     * @param target      目標保管庫
     * @param sourceDb    來源保管庫（附件與自訂圖示從這裡讀）
     * @param source      來源條目（可以是歷史版本）
     * @param withHistory 是否連歷史版本一起複製
     */
    static JacksonEntry entry(JacksonDatabase target, JacksonDatabase sourceDb, JacksonEntry source,
                              boolean withHistory) {
        JacksonEntry copy = JacksonEntry.createEntry(target);
        KdbxModelAccess.copyDeclaredFields(JacksonEntry.class, source, copy, ENTRY_SKIP);
        List<StringProperty> strings = KdbxModelAccess.strings(copy);
        strings.clear();
        for (StringProperty p : KdbxModelAccess.strings(source)) {
            // 欄位值（PropertyValue）不可變，可以共用；受保護的值維持受保護（來源不封存時重新封存）
            strings.add(new StringProperty(p.getKey(), resealed(target, p.getValue())));
        }
        List<BinaryProperty> binaries = new ArrayList<>();
        for (BinaryProperty p : KdbxModelAccess.binaries(source)) {
            byte[] content = binary(sourceDb, p);
            if (content != null) {
                binaries.add(reference(target, p.getKey(), content));
            }
        }
        KdbxModelAccess.setBinaries(copy, binaries);
        KdbxModelAccess.setEntryTimes(copy, KdbxModelAccess.copyTimes(KdbxModelAccess.entryTimes(source)));
        customIcon(target, sourceDb, copy.getCustomIconUuid());
        if (withHistory) {
            List<JacksonEntry> history = KdbxModelAccess.history(source);
            if (!history.isEmpty()) {
                List<JacksonEntry> out = KdbxModelAccess.historyForWrite(copy);
                for (JacksonEntry h : history) {
                    out.add(entry(target, sourceDb, h, false));
                }
            }
        }
        return copy;
    }

    /**
     * 跨保管庫要放進 {@code target} 的欄位值：來源是只為合併開啟、不封存的基準（{@link UnsealedValue}，T-29）時，
     * 依目標的策略重新建立（一般保管庫是 {@code SealedStore} 封存）；其他值不可變，直接共用。
     */
    static PropertyValue resealed(JacksonDatabase target, PropertyValue value) {
        if (value instanceof UnsealedValue) {
            return target.getPropertyValueStrategy().newProtected().of(value.getValueAsString());
        }
        return value;
    }

    /**
     * 複製群組本身（名稱、備註、圖示、時間與 S-Vault 看不懂的屬性），不含子群組與條目。回傳的群組還沒有上層。
     */
    static JacksonGroup group(JacksonDatabase target, JacksonDatabase sourceDb, JacksonGroup source) {
        JacksonGroup copy = JacksonGroup.createGroup(target);
        KdbxModelAccess.copyDeclaredFields(JacksonGroup.class, source, copy, GROUP_SKIP);
        KdbxModelAccess.setGroupTimes(copy, KdbxModelAccess.copyTimes(KdbxModelAccess.groupTimes(source)));
        customIcon(target, sourceDb, KdbxModelAccess.groupCustomIcon(source));
        return copy;
    }

    /**
     * 讀附件內容。
     *
     * @return 位元組；條目沒有這個附件或資料區裡找不到時為 {@code null}
     */
    static byte[] binary(JacksonDatabase db, JacksonEntry entry, String name) {
        return binary(db, EntryClasses.getBinaryProp(name, KdbxModelAccess.binaries(entry)));
    }

    /**
     * 設定附件（取代同名的），不 touch 條目。
     */
    static void putBinary(JacksonDatabase db, JacksonEntry entry, String name, byte[] content) {
        List<BinaryProperty> list = KdbxModelAccess.binaries(entry);
        BinaryProperty old = EntryClasses.getBinaryProp(name, list);
        BinaryProperty fresh = reference(db, name, content);
        if (old == null) {
            list.add(fresh);
        } else {
            list.set(list.indexOf(old), fresh);
        }
    }

    /** 移除附件參照（資料區裡沒人參照的內容由函式庫存檔時處理），不 touch 條目。 */
    static void removeBinary(JacksonEntry entry, String name) {
        List<BinaryProperty> list = KdbxModelAccess.binaries(entry);
        BinaryProperty old = EntryClasses.getBinaryProp(name, list);
        if (old != null) {
            list.remove(old);
        }
    }

    /** 目標保管庫沒有這個自訂圖示時，從來源複製一份（圖示內容與名稱相同，UUID 不變）。 */
    static void customIcon(JacksonDatabase target, JacksonDatabase sourceDb, UUID icon) {
        if (icon == null || target == sourceDb) {
            return;
        }
        KeePassFile.Meta meta = KdbxModelAccess.file(target).meta;
        if (meta.customIcons == null) {
            meta.customIcons = new ArrayList<>();
        }
        for (KeePassFile.Icon i : meta.customIcons) {
            if (icon.equals(i.uuid)) {
                return;
            }
        }
        List<KeePassFile.Icon> sourceIcons = KdbxModelAccess.file(sourceDb).meta.customIcons;
        for (KeePassFile.Icon i : sourceIcons == null ? Collections.<KeePassFile.Icon>emptyList() : sourceIcons) {
            if (icon.equals(i.uuid)) {
                KeePassFile.Icon copy = new KeePassFile.Icon();
                copy.uuid = i.uuid;
                copy.name = i.name;
                copy.lastModificationTime = i.lastModificationTime;
                copy.data = i.data == null ? null : i.data.clone();
                meta.customIcons.add(copy);
                return;
            }
        }
    }

    private static byte[] binary(JacksonDatabase db, BinaryProperty p) {
        String ref = EntryClasses.getBinaryContent(p);
        if (ref == null) {
            return null;
        }
        for (KeePassFile.Binary b : db.getBinaries()) {
            if (String.valueOf(b.getId()).equals(ref)) {
                return Helpers.decodeBase64Content(b.getValue().getBytes(), b.getCompressed());
            }
        }
        return null;
    }

    /** 把內容放進資料區（下一個沒用過的編號，和函式庫的 {@code setBinaryProperty} 相同），回傳參照。 */
    private static BinaryProperty reference(JacksonDatabase db, String name, byte[] content) {
        int max = -1;
        for (KeePassFile.Binary b : db.getBinaries()) {
            max = Math.max(max, b.getId());
        }
        db.addBinary(content, max + 1);
        BinaryProperty p = new BinaryProperty();
        p.setKey(name);
        BinaryProperty.Value value = new BinaryProperty.Value();
        value.setRef(String.valueOf(max + 1));
        p.setValue(value);
        return p;
    }
}
