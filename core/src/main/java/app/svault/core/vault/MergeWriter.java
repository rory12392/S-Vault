package app.svault.core.vault;

import static app.svault.core.vault.VaultTree.findEntry;
import static app.svault.core.vault.VaultTree.findGroup;
import static app.svault.core.vault.VaultTree.keepingGroupTimes;

import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.PropertyValue;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;
import org.linguafranca.pwdb.kdbx.jackson.model.KeePassFile;
import org.linguafranca.pwdb.kdbx.jackson.model.Times;

/**
 * 把 {@link MergePlan} 套用到遠端那份資料庫物件（Architecture §4 第 7 點）：只改計畫列出的部分，內容從本機那份複製，
 * 其餘（包含 S-Vault 看不懂的元素）原樣保留。只改記憶體；寫回遠端仍由呼叫端經 {@code SaveCoordinator}。
 *
 * <p>時間：函式庫的寫入 API 會把修改時間改成當下（帶毫秒），這裡一律改回計畫指定的值（或原值），合併本身不產生
 * 「新的修改」。條目或子群組移進移出不改群組的修改時間（同 {@link VaultEditor}）。
 */
public final class MergeWriter {

    private MergeWriter() {
    }

    /**
     * 套用合併計畫。
     *
     * @param target 遠端那份（被修改）
     * @param source 本機那份（只讀；計畫裡「從本機」的內容從這裡複製）
     * @param plan   {@code ThreeWayMerger} 以這兩份的快照算出的計畫
     * @throws IllegalArgumentException 兩份不是同一個保管庫（根群組 UUID 不同）
     * @throws IllegalStateException    計畫和保管庫內容對不上（快照之後又被修改過），或保管庫已上鎖
     */
    public static void apply(Vault target, Vault source, MergePlan plan) {
        if (!target.id().equals(source.id())) {
            throw new IllegalArgumentException("不是同一個保管庫");
        }
        JacksonDatabase db = target.database();
        JacksonDatabase src = source.database();
        for (MergePlan.GroupChange c : plan.groups()) {
            applyGroup(db, src, c);
        }
        if (plan.recycleBinId() != null) {
            KeePassFile.Meta meta = KdbxModelAccess.file(db).meta;
            meta.recycleBinUUID = plan.recycleBinId();
            meta.recycleBinChanged = KdbxModelAccess.file(src).meta.recycleBinChanged;
        }
        int maxHistory = target.historyMaxItems();
        for (MergePlan.EntryChange c : plan.entries()) {
            applyEntry(db, src, c, maxHistory);
        }
        for (UUID id : plan.removedEntries()) {
            JacksonEntry e = findEntry(db.getRootGroup(), id);
            if (e != null) {
                JacksonGroup parent = e.getParent();
                keepingGroupTimes(() -> parent.removeEntry(e), parent);
            }
        }
        for (UUID id : plan.removedGroups()) {
            removeGroup(db, id);
        }
        if (plan.deletedObjects() != null) {
            Map<UUID, Date> deleted = new LinkedHashMap<>();
            for (Map.Entry<UUID, Instant> d : plan.deletedObjects().entrySet()) {
                deleted.put(d.getKey(), Date.from(d.getValue()));
            }
            KdbxModelAccess.setDeletedObjects(db, deleted);
        }
    }

    private static void applyGroup(JacksonDatabase db, JacksonDatabase src, MergePlan.GroupChange c) {
        JacksonGroup root = db.getRootGroup();
        if (c.add()) {
            JacksonGroup copy = KdbxCopy.group(db, src, required(findGroup(src.getRootGroup(), c.uuid())));
            JacksonGroup parent = groupOrRoot(db, c.parentId());
            keepingGroupTimes(() -> parent.addGroup(copy), parent);
            return;
        }
        JacksonGroup g = required(findGroup(root, c.uuid()));
        JacksonGroup s = c.fieldsFromLocal().isEmpty() ? null : required(findGroup(src.getRootGroup(), c.uuid()));
        for (String key : c.fieldsFromLocal()) {
            switch (key) {
                case GroupSnapshot.NAME:
                    KdbxModelAccess.setGroupName(g, s.getName());
                    break;
                case GroupSnapshot.NOTES:
                    KdbxModelAccess.setGroupNotes(g, KdbxModelAccess.groupNotes(s));
                    break;
                case GroupSnapshot.ICON:
                    KdbxModelAccess.setGroupIcon(g, KdbxModelAccess.groupIconId(s), KdbxModelAccess.groupCustomIcon(s));
                    KdbxCopy.customIcon(db, src, KdbxModelAccess.groupCustomIcon(s));
                    break;
                default:
                    throw new IllegalStateException("不認得的群組欄位");
            }
        }
        if (g != root && c.parentId() != null && !c.parentId().equals(g.getParent().getUuid())) {
            JacksonGroup from = g.getParent();
            JacksonGroup to = groupOrRoot(db, c.parentId());
            if (findGroup(g, to.getUuid()) == null) {
                // addGroup 會先從原本的上層移除，兩邊的修改時間都會被函式庫改掉
                keepingGroupTimes(() -> to.addGroup(g), from, to);
            }
        }
        Times times = KdbxModelAccess.groupTimes(g);
        if (c.lastModified() != null) {
            times.setLastModificationTime(Date.from(c.lastModified()));
        }
        if (c.locationChanged() != null) {
            times.setLocationChanged(Date.from(c.locationChanged()));
        }
    }

    private static void applyEntry(JacksonDatabase db, JacksonDatabase src, MergePlan.EntryChange c, int maxHistory) {
        if (c.add()) {
            JacksonEntry copy = KdbxCopy.entry(db, src, required(findEntry(src.getRootGroup(), c.uuid())), true);
            JacksonGroup group = groupOrRoot(db, c.groupId());
            keepingGroupTimes(() -> group.addEntry(copy), group);
            trimHistory(copy, maxHistory);
            return;
        }
        JacksonEntry t = required(findEntry(db.getRootGroup(), c.uuid()));
        boolean needsSource = !c.fieldsFromLocal().isEmpty() || c.pushLocalCurrent() || !c.historyFromLocal().isEmpty();
        JacksonEntry s = needsSource ? required(findEntry(src.getRootGroup(), c.uuid())) : null;
        Date modified = KdbxModelAccess.entryTimes(t).getLastModificationTime();

        List<JacksonEntry> history = null;
        if (c.pushRemoteCurrent() || c.pushLocalCurrent() || !c.historyFromLocal().isEmpty()) {
            history = KdbxModelAccess.historyForWrite(t);
        }
        if (c.pushRemoteCurrent()) {
            history.add(KdbxModelAccess.copyForHistory(db, t));
        }
        if (c.pushLocalCurrent()) {
            history.add(KdbxCopy.entry(db, src, s, false));
        }
        for (Instant time : c.historyFromLocal()) {
            for (JacksonEntry h : KdbxModelAccess.history(s)) {
                if (h.getLastModificationTime() != null && h.getLastModificationTime().toInstant().equals(time)) {
                    history.add(KdbxCopy.entry(db, src, h, false));
                    break;
                }
            }
        }
        if (history != null) {
            // KDBX 的歷史版本最舊的在前；穩定排序，同一時間的維持加入順序
            history.sort(Comparator.comparing(JacksonEntry::getLastModificationTime,
                    Comparator.nullsFirst(Comparator.naturalOrder())));
        }

        for (String key : c.fieldsFromLocal()) {
            copyField(db, src, s, t, key);
        }
        Times times = KdbxModelAccess.entryTimes(t);
        // removeProperty／setPropertyValue（新欄位）會 touch；改回原值，再依計畫設定
        times.setLastModificationTime(modified);
        if (c.lastModified() != null) {
            times.setLastModificationTime(Date.from(c.lastModified()));
        }
        if (c.groupId() != null && !c.groupId().equals(t.getParent().getUuid())) {
            JacksonGroup from = t.getParent();
            JacksonGroup to = groupOrRoot(db, c.groupId());
            keepingGroupTimes(() -> to.addEntry(t), from, to);
        }
        if (c.locationChanged() != null) {
            times.setLocationChanged(Date.from(c.locationChanged()));
        }
        trimHistory(t, maxHistory);
    }

    private static void copyField(JacksonDatabase db, JacksonDatabase src, JacksonEntry s, JacksonEntry t,
                                  String key) {
        if (key.startsWith(EntrySnapshot.STRING_PREFIX)) {
            String name = key.substring(EntrySnapshot.STRING_PREFIX.length());
            PropertyValue value = s.getPropertyValue(name);
            if (value != null) {
                t.setPropertyValue(name, KdbxCopy.resealed(db, value));
            } else if (Entry.STANDARD_PROPERTY_NAMES.contains(name)) {
                // 標準五欄不能移除（函式庫會拋例外）；本機沒有時等於空字串
                t.setPropertyValue(name, db.getPropertyValueStrategy().newUnprotected().of(""));
            } else {
                t.removeProperty(name);
            }
        } else if (key.startsWith(EntrySnapshot.ATTACHMENT_PREFIX)) {
            String name = key.substring(EntrySnapshot.ATTACHMENT_PREFIX.length());
            byte[] content = KdbxCopy.binary(src, s, name);
            if (content == null) {
                KdbxCopy.removeBinary(t, name);
            } else {
                KdbxCopy.putBinary(db, t, name, content);
            }
        } else if (key.equals(EntrySnapshot.TAGS)) {
            KdbxModelAccess.setTags(t, KdbxModelAccess.tags(s));
        } else if (key.equals(EntrySnapshot.ICON)) {
            KdbxModelAccess.setEntryIcon(t, KdbxModelAccess.entryIconId(s), s.getCustomIconUuid());
            KdbxCopy.customIcon(db, src, s.getCustomIconUuid());
        } else if (key.equals(EntrySnapshot.EXPIRY)) {
            Times from = KdbxModelAccess.entryTimes(s);
            Times to = KdbxModelAccess.entryTimes(t);
            to.setExpires(from.getExpires());
            to.setExpiryTime(from.getExpiryTime());
        } else {
            throw new IllegalStateException("不認得的條目欄位");
        }
    }

    private static void removeGroup(JacksonDatabase db, UUID id) {
        JacksonGroup root = db.getRootGroup();
        JacksonGroup g = findGroup(root, id);
        if (g == null || g == root) {
            return;
        }
        // 合併計畫已經先移走或移除要保留的子項目；還剩下的（不該發生）移到根群組，不跟著群組消失
        for (JacksonEntry e : g.getEntries()) {
            keepingGroupTimes(() -> root.addEntry(e), g, root);
        }
        for (JacksonGroup child : g.getGroups()) {
            keepingGroupTimes(() -> root.addGroup(child), g, root);
        }
        JacksonGroup parent = g.getParent();
        keepingGroupTimes(() -> parent.removeGroup(g), parent);
    }

    private static void trimHistory(JacksonEntry entry, int max) {
        List<JacksonEntry> history = KdbxModelAccess.history(entry);
        // KeePassXC：-1 表示不限制
        while (max >= 0 && history.size() > max) {
            history.remove(0);
        }
    }

    private static JacksonGroup groupOrRoot(JacksonDatabase db, UUID id) {
        JacksonGroup g = id == null ? null : findGroup(db.getRootGroup(), id);
        return g == null ? db.getRootGroup() : g;
    }

    private static <T> T required(T item) {
        if (item == null) {
            throw new IllegalStateException("合併計畫和保管庫內容對不上（快照之後又被修改過）");
        }
        return item;
    }
}
