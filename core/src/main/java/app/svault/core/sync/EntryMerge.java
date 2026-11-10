package app.svault.core.sync;

import app.svault.core.sync.MergeState.Pick;
import app.svault.core.vault.EntrySnapshot;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 條目的合併（Architecture §4 第 2–6 點）。每筆條目得到一個 {@link Outcome}；所在群組在 {@link GroupMerge} 之後
 * 還可能被調整（群組不存在時移到根群組、跟著刪除那一邊放進回收桶）。
 */
final class EntryMerge {

    /** 一筆條目的決定（可變，只在一次合併裡用）。 */
    static final class Outcome {
        final UUID id;
        final EntrySnapshot local;
        final EntrySnapshot remote;
        /** 合併後還在。 */
        boolean present;
        /** 遠端沒有，從本機整筆複製（含歷史版本）。 */
        boolean add;
        final Set<String> fromLocal = new TreeSet<>();
        /** 取遠端的值、而且和本機不同的欄位（本機的內容會跟著改）。 */
        final Set<String> fromRemoteChanged = new TreeSet<>();
        final Set<String> conflicts = new TreeSet<>();
        UUID groupId;
        boolean groupFromLocal;
        /** 合併後的位置是跟著這一邊的刪除（放進回收桶）；被保留的群組不能把它帶出回收桶。 */
        Side followsDeletionOf;
        boolean pushRemote;
        boolean pushLocal;
        Instant lastModified;

        Outcome(UUID id, EntrySnapshot local, EntrySnapshot remote) {
            this.id = id;
            this.local = local;
            this.remote = remote;
        }
    }

    private EntryMerge() {
    }

    static void mergeAll(MergeState s) {
        Set<UUID> ids = new LinkedHashSet<>(s.remote.entries().keySet());
        ids.addAll(s.local.entries().keySet());
        for (UUID id : ids) {
            Outcome o = new Outcome(id, s.local.entries().get(id), s.remote.entries().get(id));
            if (o.local != null && o.remote != null) {
                both(s, o);
            } else if (o.local != null) {
                onlyLocal(s, o);
            } else {
                onlyRemote(s, o);
            }
            s.entries.put(id, o);
        }
    }

    /** 遠端沒有：基準有＝遠端刪除（本機有修改才保留，AC-07-4）；基準沒有＝本機新增（第 3 點）。 */
    private static void onlyLocal(MergeState s, Outcome o) {
        boolean deletedRemotely = s.hasBase() && s.base.entries().containsKey(o.id);
        if (deletedRemotely && !s.entryModifiedOn(Side.LOCAL, o.id)) {
            return;
        }
        if (!s.hasBase() && deletedAfter(s.remote, o.id, o.local)) {
            s.notice(MergeNotice.Kind.DELETED_BY_RECORD, o.id);
            return;
        }
        o.present = true;
        o.add = true;
        o.groupId = o.local.groupId();
        o.groupFromLocal = true;
        if (deletedRemotely) {
            s.notice(MergeNotice.Kind.KEPT_LOCAL_EDIT, o.id);
        }
    }

    /** 本機沒有：基準有＝本機刪除（遠端沒修改就從遠端移除）；基準沒有＝遠端新增，不用改。 */
    private static void onlyRemote(MergeState s, Outcome o) {
        boolean deletedLocally = s.hasBase() && s.base.entries().containsKey(o.id);
        if (deletedLocally && !s.entryModifiedOn(Side.REMOTE, o.id)) {
            return;
        }
        if (!s.hasBase() && deletedAfter(s.local, o.id, o.remote)) {
            s.notice(MergeNotice.Kind.DELETED_BY_RECORD, o.id);
            return;
        }
        o.present = true;
        o.groupId = o.remote.groupId();
        if (deletedLocally) {
            s.notice(MergeNotice.Kind.KEPT_REMOTE_EDIT, o.id);
        }
    }

    /**
     * 沒有基準時（第 4、6 點）判斷「另一邊刪了」只能看刪除紀錄：{@code other} 記的刪除時間比這筆條目最後修改晚（T-99，D-190；
     * KeePass 2 的做法）。比的是兩台的時鐘，所以結果一律產生通知。
     */
    private static boolean deletedAfter(VaultSnapshot other, UUID id, EntrySnapshot entry) {
        Instant deleted = other.deleted().get(id);
        return deleted != null && deleted.isAfter(entry.lastModified());
    }

    private static void both(MergeState s, Outcome o) {
        o.present = true;
        EntrySnapshot b = s.hasBase() ? s.base.entries().get(o.id) : null;
        boolean hasBase = b != null;
        Side newer = MergeState.newer(o.local.lastModified(), o.remote.lastModified());
        Map<String, Side> chosen = s.choices.get(o.id);

        Set<String> keys = new TreeSet<>(o.local.fields().keySet());
        keys.addAll(o.remote.fields().keySet());
        if (hasBase) {
            keys.addAll(b.fields().keySet());
        }
        for (String key : keys) {
            Pick p = MergeState.pick(hasBase, hasBase ? b.fields().get(key) : null, o.local.fields().get(key),
                    o.remote.fields().get(key), newer);
            apply(o, key, p, chosen, false);
        }

        // 所在群組：一邊把它移進回收桶（刪除）、另一邊有修改 → 保留修改，放在修改那一邊的位置（AC-07-4）
        boolean localDeleted = s.entryDeletedOn(Side.LOCAL, o.id);
        boolean remoteDeleted = s.entryDeletedOn(Side.REMOTE, o.id);
        if (localDeleted && !remoteDeleted && s.entryModifiedOn(Side.REMOTE, o.id)) {
            groupFrom(o, Side.REMOTE);
            s.notice(MergeNotice.Kind.KEPT_REMOTE_EDIT, o.id);
        } else if (remoteDeleted && !localDeleted && s.entryModifiedOn(Side.LOCAL, o.id)) {
            groupFrom(o, Side.LOCAL);
            s.notice(MergeNotice.Kind.KEPT_LOCAL_EDIT, o.id);
        } else {
            // 沒有基準時，移動看 LocationChanged（移動不改 LastModificationTime；第 6 點，T-13 決定）
            Side newerMove = MergeState.newer(o.local.locationChanged(), o.remote.locationChanged());
            Pick p = MergeState.pick(hasBase, hasBase ? b.groupId().toString() : null,
                    o.local.groupId().toString(), o.remote.groupId().toString(), newerMove);
            apply(o, Conflict.GROUP, p, chosen, true);
            if (localDeleted && o.groupId.equals(o.local.groupId())) {
                o.followsDeletionOf = Side.LOCAL;
            } else if (remoteDeleted && o.groupId.equals(o.remote.groupId())) {
                o.followsDeletionOf = Side.REMOTE;
            }
        }

        if (!hasBase && !contentEquals(o)) {
            // 第 6 點：以較新的為準，較舊的那一份推入歷史紀錄
            if (newer == Side.LOCAL) {
                o.pushRemote = true;
            } else {
                o.pushLocal = true;
            }
        }
        boolean contentChosen = chosen != null && !chosen.keySet().equals(Collections.singleton(Conflict.GROUP));
        if (contentChosen) {
            // AC-08-2：衝突解決後，和結果不同的那一邊推入歷史紀錄；解決算一次修改
            o.pushRemote = !o.fromLocal.isEmpty();
            o.pushLocal = !o.fromRemoteChanged.isEmpty();
            o.lastModified = s.resolvedAt;
        } else if (!o.fromLocal.isEmpty()) {
            o.lastModified = MergeState.max(o.local.lastModified(), o.remote.lastModified());
        }
        if (!o.conflicts.isEmpty()) {
            List<Conflict.Field> fields = new ArrayList<>();
            for (String key : o.conflicts) {
                fields.add(key.equals(Conflict.GROUP)
                        ? new Conflict.Field(key, o.local.groupId().toString(), o.remote.groupId().toString())
                        : new Conflict.Field(key, o.local.fields().get(key), o.remote.fields().get(key)));
            }
            s.conflicts.add(new Conflict(o.id, fields, o.local.lastModified(), o.remote.lastModified()));
        }
    }

    private static void apply(Outcome o, String key, Pick p, Map<String, Side> chosen, boolean group) {
        if (p == Pick.CONFLICT) {
            Side c = chosen == null ? null : chosen.get(key);
            if (c == null) {
                o.conflicts.add(key);
                p = Pick.SAME;
            } else {
                p = c == Side.LOCAL ? Pick.LOCAL : Pick.REMOTE;
            }
        }
        if (group) {
            // 位置不算版本內容（歷史版本不記所在群組），不放進 fromRemoteChanged
            groupFrom(o, p == Pick.LOCAL ? Side.LOCAL : Side.REMOTE);
            return;
        }
        if (p == Pick.LOCAL) {
            o.fromLocal.add(key);
        } else if (p == Pick.REMOTE) {
            o.fromRemoteChanged.add(key);
        }
    }

    private static void groupFrom(Outcome o, Side side) {
        o.groupFromLocal = side == Side.LOCAL && !o.local.groupId().equals(o.remote.groupId());
        o.groupId = side == Side.LOCAL ? o.local.groupId() : o.remote.groupId();
    }

    private static boolean contentEquals(Outcome o) {
        return o.local.fields().equals(o.remote.fields());
    }

    /** @return 本機有、遠端沒有的歷史版本（以修改時間去重；聯集，第 5 點） */
    static List<Instant> historyFromLocal(Outcome o) {
        Set<Instant> remote = new HashSet<>(o.remote.historyTimes());
        if (o.pushRemote) {
            // 遠端目前的版本會被推入歷史；本機歷史裡同一時間的那份就是它
            remote.add(o.remote.lastModified());
        }
        List<Instant> out = new ArrayList<>();
        for (Instant t : o.local.historyTimes()) {
            if (remote.add(t)) {
                out.add(t);
            }
        }
        return out;
    }
}
