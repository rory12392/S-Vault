package app.svault.core.vault;

import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.linguafranca.pwdb.PropertyValue;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.model.KeePassFile;
import org.linguafranca.pwdb.kdbx.jackson.model.Times;

/**
 * {@link VaultEditor} 用的群組樹走訪、資源回收桶、欄位寫入與時間工具（從編輯器拆出，CONVENTIONS §10 的檔案長度）。
 *
 * <p>自己走訪而不用函式庫的 {@code findGroup}：它會略過資源回收桶底下的群組，還原與永久刪除時找不到。
 * 也不用 {@code JacksonGroup.isRecycleBin()}：檔案沒有 {@code RecycleBinUUID} 時它會拋 {@code NullPointerException}。
 */
final class VaultTree {

    /** 新建資源回收桶的名稱（S-Vault 建立的保管庫第一次刪除時；KeePassXC 的檔案通常已經有）。 */
    static final String RECYCLE_BIN_NAME = "資源回收桶";
    /** KeePass 標準圖示中的垃圾桶（KeePassXC 建立回收桶時也用這個）。 */
    private static final int RECYCLE_BIN_ICON = 43;

    private VaultTree() {
    }

    /** 取得資源回收桶；不存在時在根群組下建立，並記進 {@code Meta/RecycleBinUUID}。 */
    static JacksonGroup recycleBin(JacksonDatabase db, Date now) {
        KeePassFile.Meta meta = KdbxModelAccess.file(db).meta;
        JacksonGroup root = db.getRootGroup();
        JacksonGroup bin = meta.recycleBinUUID == null ? null : findGroup(root, meta.recycleBinUUID);
        if (bin != null) {
            return bin;
        }
        JacksonGroup created = db.newGroup();
        created.setName(RECYCLE_BIN_NAME);
        created.setIcon(db.newIcon(RECYCLE_BIN_ICON));
        stampNew(KdbxModelAccess.groupTimes(created), now);
        keepingGroupTimes(() -> root.addGroup(created), root);
        meta.recycleBinUUID = created.getUuid();
        meta.recycleBinChanged = now;
        return created;
    }

    /** @return 還原的目的地：刪除前的群組；它已不存在或也在資源回收桶裡時為根群組 */
    static JacksonGroup restoreTarget(JacksonDatabase db, UUID previousParent) {
        JacksonGroup root = db.getRootGroup();
        JacksonGroup target = previousParent == null ? null : findGroup(root, previousParent);
        return target == null || inRecycleBin(db, target) ? root : target;
    }

    /** @return 條目（含資源回收桶裡的，不含歷史版本）；找不到時為 {@code null} */
    static JacksonEntry findEntry(JacksonGroup group, UUID id) {
        for (JacksonEntry e : group.getEntries()) {
            if (e.getUuid().equals(id)) {
                return e;
            }
        }
        for (JacksonGroup child : group.getGroups()) {
            JacksonEntry found = findEntry(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** @return 群組（含資源回收桶與其中的群組）；找不到時為 {@code null} */
    static JacksonGroup findGroup(JacksonGroup group, UUID id) {
        if (group.getUuid().equals(id)) {
            return group;
        }
        for (JacksonGroup child : group.getGroups()) {
            JacksonGroup found = findGroup(child, id);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    static boolean isRecycleBin(JacksonDatabase db, JacksonGroup group) {
        return group.getUuid().equals(KdbxModelAccess.file(db).meta.recycleBinUUID);
    }

    /** @return 群組是資源回收桶本身或在它底下 */
    static boolean inRecycleBin(JacksonDatabase db, JacksonGroup group) {
        UUID bin = KdbxModelAccess.file(db).meta.recycleBinUUID;
        for (JacksonGroup g = group; g != null; g = g.getParent()) {
            if (g.getUuid().equals(bin)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 寫入欄位值：原本受保護的維持受保護，否則依檔案的保護設定（密碼預設受保護）。不能用 {@code setPassword}／
     * {@code setProperty}，它們一律寫成不保護的值（P15）。
     */
    static void setValue(JacksonDatabase db, JacksonEntry entry, String name, String value) {
        PropertyValue old = entry.getPropertyValue(name);
        PropertyValue.Strategy strategy = db.getPropertyValueStrategy();
        PropertyValue.Factory<? extends PropertyValue> factory = old != null && old.isProtected()
                ? strategy.newProtected() : strategy.getFactoryFor(name);
        entry.setPropertyValue(name, factory.of(value));
    }

    /** 群組本身、所有子群組與條目的 UUID（永久刪除時寫進 {@code DeletedObjects}）。 */
    static void collectIds(JacksonGroup group, List<UUID> out) {
        out.add(group.getUuid());
        for (JacksonEntry e : group.getEntries()) {
            out.add(e.getUuid());
        }
        for (JacksonGroup child : group.getGroups()) {
            collectIds(child, out);
        }
    }

    /**
     * 執行函式庫的操作，之後把這些群組的修改時間改回原值：函式庫放入或移出時會 touch 群組（帶毫秒的當下時間），
     * 但條目或子群組移進移出不算群組本身被修改（和 KeePassXC 相同），也不能留下毫秒（讀回驗證）。
     */
    static void keepingGroupTimes(Runnable action, JacksonGroup... groups) {
        Date[] kept = new Date[groups.length];
        for (int i = 0; i < groups.length; i++) {
            kept[i] = KdbxModelAccess.groupTimes(groups[i]).getLastModificationTime();
        }
        action.run();
        for (int i = 0; i < groups.length; i++) {
            KdbxModelAccess.groupTimes(groups[i]).setLastModificationTime(kept[i]);
        }
    }

    /** 新建的條目或群組：所有時間設成同一個（已截到秒的）時間，取代函式庫用系統時鐘填的值。 */
    static void stampNew(Times times, Date now) {
        times.setCreationTime(now);
        times.setLastModificationTime(now);
        times.setLastAccessTime(now);
        times.setLocationChanged(now);
        times.setExpiryTime(now);
    }
}
