package app.svault.core.vault;

import static app.svault.core.vault.VaultTree.findEntry;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;

/**
 * 還原舊備份時，讓寫回遠端的內容成為「從目前內容延伸出來的」版本（AC-09-2、AC-07-9；Architecture §5，T-27）。
 *
 * <p>其他裝置以「遠端是不是從基準延伸出來的」區分「被整份取代」（兩邊都保留）與一般的修改（三方合併）。備份的原始內容
 * 看起來和被取代一樣（目前有的條目不見了、改過的條目歷史裡沒有目前的版本），刪除就不會生效。所以還原時：
 * <ul>
 *   <li>目前有、備份沒有的條目與群組 → 記進 {@code DeletedObjects}（時間＝還原的時間）；目前的刪除紀錄也帶過來；</li>
 *   <li>備份與目前內容不同的條目 → 目前的版本與它的歷史紀錄推入備份那筆的歷史紀錄，修改時間設成還原的時間
 *       （還原是一次修改；被換掉的版本因此也還留在歷史紀錄裡）。</li>
 * </ul>
 * 條目的內容、所在群組與群組都和備份相同，只多了歷史與刪除紀錄。
 */
public final class RestoreMarks {

    private RestoreMarks() {
    }

    /**
     * 在還原的內容上加註記（只改記憶體；寫回仍由 {@code SaveCoordinator}）。
     *
     * @param restored 以備份內容開啟的保管庫（被修改）
     * @param current  目前的內容（開啟中的保管庫、遠端或基準；只讀，不同保管庫或已上鎖的略過）
     * @param now      還原的時間（截到秒）
     * @return 有加註記（要重新序列化）；備份和目前內容相同時為 {@code false}
     */
    public static boolean apply(Vault restored, List<Vault> current, Instant now) {
        Instant time = now.truncatedTo(ChronoUnit.SECONDS);
        VaultSnapshot backup = restored.snapshot();
        JacksonDatabase db = restored.database();
        int maxHistory = restored.historyMaxItems();
        Map<UUID, Instant> deleted = new LinkedHashMap<>(backup.deleted());
        Set<UUID> marked = new HashSet<>();
        for (Vault c : current) {
            if (c == null || c.isClosed() || !c.id().equals(restored.id())) {
                continue;
            }
            VaultSnapshot state = c.snapshot();
            for (Map.Entry<UUID, Instant> d : state.deleted().entrySet()) {
                if (!backup.entries().containsKey(d.getKey()) && !backup.groups().containsKey(d.getKey())) {
                    deleted.merge(d.getKey(), d.getValue(), (a, b) -> a.isAfter(b) ? a : b);
                }
            }
            for (EntrySnapshot e : state.entries().values()) {
                EntrySnapshot old = backup.entries().get(e.uuid());
                if (old == null) {
                    deleted.putIfAbsent(e.uuid(), time);
                } else if (!marked.contains(e.uuid()) && !sameVersion(old, e)) {
                    pushCurrent(db, c.database(), e.uuid(), maxHistory, time);
                    marked.add(e.uuid());
                }
            }
            for (GroupSnapshot g : state.groups().values()) {
                if (!backup.groups().containsKey(g.uuid())) {
                    deleted.putIfAbsent(g.uuid(), time);
                }
            }
        }
        boolean deletionsChanged = !deleted.equals(backup.deleted());
        if (deletionsChanged) {
            Map<UUID, Date> out = new LinkedHashMap<>();
            for (Map.Entry<UUID, Instant> d : deleted.entrySet()) {
                out.put(d.getKey(), Date.from(d.getValue()));
            }
            KdbxModelAccess.setDeletedObjects(db, out);
        }
        return deletionsChanged || !marked.isEmpty();
    }

    /** @return 兩份是同一個版本，或目前的版本已在備份那筆的歷史紀錄裡 */
    private static boolean sameVersion(EntrySnapshot backup, EntrySnapshot current) {
        return backup.fields().equals(current.fields()) || backup.lastModified().equals(current.lastModified())
                || backup.historyTimes().contains(current.lastModified());
    }

    // 目前的版本＋它的歷史紀錄（以修改時間去重）推入備份那筆的歷史紀錄，修改時間設成還原的時間
    private static void pushCurrent(JacksonDatabase db, JacksonDatabase currentDb, UUID id, int maxHistory,
                                    Instant time) {
        JacksonEntry target = findEntry(db.getRootGroup(), id);
        JacksonEntry source = findEntry(currentDb.getRootGroup(), id);
        if (target == null || source == null) {
            return;
        }
        List<JacksonEntry> history = KdbxModelAccess.historyForWrite(target);
        Set<Instant> times = new HashSet<>();
        // 備份那筆目前的版本本身不用再進歷史（目前內容的歷史裡常有同一個版本）
        times.add(instant(target));
        for (JacksonEntry h : history) {
            times.add(instant(h));
        }
        for (JacksonEntry h : KdbxModelAccess.history(source)) {
            if (times.add(instant(h))) {
                history.add(KdbxCopy.entry(db, currentDb, h, false));
            }
        }
        if (times.add(instant(source))) {
            history.add(KdbxCopy.entry(db, currentDb, source, false));
        }
        // KDBX 的歷史版本最舊的在前；超過上限時刪最舊的（KeePassXC：-1 表示不限制）
        history.sort(Comparator.comparing(RestoreMarks::instant));
        while (maxHistory >= 0 && history.size() > maxHistory) {
            history.remove(0);
        }
        KdbxModelAccess.entryTimes(target).setLastModificationTime(Date.from(time));
    }

    private static Instant instant(JacksonEntry e) {
        Date d = e.getLastModificationTime();
        return d == null ? Instant.EPOCH : d.toInstant().truncatedTo(ChronoUnit.SECONDS);
    }
}
