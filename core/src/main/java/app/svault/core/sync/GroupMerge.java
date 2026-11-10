package app.svault.core.sync;

import app.svault.core.sync.MergeState.Pick;
import app.svault.core.vault.GroupSnapshot;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 群組的合併（Architecture §4 第 4 點的群組規則）。欄位同條目的逐欄規則，但群組沒有歷史紀錄、也不進衝突清單：
 * 兩邊都改了同一個欄位時以較新的為準並產生通知（T-13 決定）。
 *
 * <p>一邊刪除群組（整個移除，或移進回收桶）、另一邊在裡面新增或修改了東西 → 保留群組與那些東西，其餘沒動的照刪除
 * 那一邊處理（移除，或留在回收桶）。「保留」會往上傳：被保留的群組的上層若也被刪除，一併保留。
 */
final class GroupMerge {

    /** 一個群組的決定（可變，只在一次合併裡用）。 */
    static final class Outcome {
        final UUID id;
        final GroupSnapshot local;
        final GroupSnapshot remote;
        boolean present;
        /** 遠端沒有，從本機複製。 */
        boolean add;
        final Set<String> fromLocal = new TreeSet<>();
        UUID parentId;
        boolean parentFromLocal;
        /** 被這一邊刪除、另一邊沒有修改：照刪除處理，除非裡面有要保留的東西（{@link #rescue}）。 */
        Side deletedBy;
        boolean rescued;
        Instant lastModified;

        Outcome(UUID id, GroupSnapshot local, GroupSnapshot remote) {
            this.id = id;
            this.local = local;
            this.remote = remote;
        }

        /** @return 合併後的位置是跟著刪除那一邊（移除，或放在回收桶裡） */
        boolean followsDeletion() {
            return deletedBy != null && !rescued;
        }
    }

    private GroupMerge() {
    }

    static void mergeAll(MergeState s) {
        Set<UUID> ids = new LinkedHashSet<>(s.remote.groups().keySet());
        ids.addAll(s.local.groups().keySet());
        for (UUID id : ids) {
            Outcome o = new Outcome(id, s.local.groups().get(id), s.remote.groups().get(id));
            if (o.local != null && o.remote != null) {
                both(s, o);
            } else if (o.local != null) {
                boolean deletedRemotely = s.hasBase() && s.base.groups().containsKey(id);
                o.present = !deletedRemotely || s.groupModifiedOn(Side.LOCAL, id);
                o.add = true;
                o.parentId = o.local.parentId();
                o.parentFromLocal = true;
                if (deletedRemotely && o.present) {
                    s.notice(MergeNotice.Kind.GROUP_KEPT, id);
                } else if (deletedRemotely) {
                    o.deletedBy = Side.REMOTE;
                }
            } else {
                boolean deletedLocally = s.hasBase() && s.base.groups().containsKey(id);
                o.present = !deletedLocally || s.groupModifiedOn(Side.REMOTE, id);
                o.parentId = o.remote.parentId();
                if (deletedLocally && o.present) {
                    s.notice(MergeNotice.Kind.GROUP_KEPT, id);
                } else if (deletedLocally) {
                    o.deletedBy = Side.LOCAL;
                }
            }
            s.groups.put(id, o);
        }
    }

    private static void both(MergeState s, Outcome o) {
        o.present = true;
        GroupSnapshot b = s.hasBase() ? s.base.groups().get(o.id) : null;
        boolean hasBase = b != null;
        Side newer = MergeState.newer(o.local.lastModified(), o.remote.lastModified());
        boolean newerKept = false;
        Set<String> keys = new TreeSet<>(o.local.fields().keySet());
        keys.addAll(o.remote.fields().keySet());
        for (String key : keys) {
            Pick p = MergeState.pick(hasBase, hasBase ? b.fields().get(key) : null, o.local.fields().get(key),
                    o.remote.fields().get(key), newer);
            if (p == Pick.CONFLICT) {
                newerKept = true;
                p = newer == Side.LOCAL ? Pick.LOCAL : Pick.REMOTE;
            }
            if (p == Pick.LOCAL) {
                o.fromLocal.add(key);
            }
        }
        if (!o.fromLocal.isEmpty()) {
            o.lastModified = MergeState.max(o.local.lastModified(), o.remote.lastModified());
        }

        if (o.id.equals(s.root)) {
            o.parentId = null;
        } else {
            boolean localDeleted = s.groupDeletedOn(Side.LOCAL, o.id);
            boolean remoteDeleted = s.groupDeletedOn(Side.REMOTE, o.id);
            if (localDeleted && !remoteDeleted && s.groupModifiedOn(Side.REMOTE, o.id)) {
                parentFrom(o, Side.REMOTE);
                s.notice(MergeNotice.Kind.GROUP_KEPT, o.id);
            } else if (remoteDeleted && !localDeleted && s.groupModifiedOn(Side.LOCAL, o.id)) {
                parentFrom(o, Side.LOCAL);
                s.notice(MergeNotice.Kind.GROUP_KEPT, o.id);
            } else {
                Side newerMove = MergeState.newer(o.local.locationChanged(), o.remote.locationChanged());
                Pick p = MergeState.pick(hasBase, hasBase ? str(b.parentId()) : null, str(o.local.parentId()),
                        str(o.remote.parentId()), newerMove);
                if (p == Pick.CONFLICT) {
                    newerKept = true;
                    p = newerMove == Side.LOCAL ? Pick.LOCAL : Pick.REMOTE;
                }
                parentFrom(o, p == Pick.LOCAL ? Side.LOCAL : Side.REMOTE);
                if (localDeleted != remoteDeleted) {
                    Side deleter = localDeleted ? Side.LOCAL : Side.REMOTE;
                    if (Objects.equals(o.parentId, s.side(deleter).groups().get(o.id).parentId())) {
                        o.deletedBy = deleter;
                    }
                }
            }
        }
        if (newerKept) {
            s.notice(MergeNotice.Kind.GROUP_NEWER_KEPT, o.id);
        }
    }

    /**
     * 被刪除的群組裡有要保留的東西（沒有跟著刪除的條目或群組）時保留它，重複到沒有新的為止（往上傳）。
     */
    static void rescue(MergeState s) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Outcome o : s.groups.values()) {
                if (o.deletedBy == null || o.rescued || !hasKeptChild(s, o.id)) {
                    continue;
                }
                o.rescued = true;
                changed = true;
                Side keeper = MergeState.other(o.deletedBy);
                if (!o.present) {
                    // 整個被移除的那一邊沒有它：用另一邊的資料保留
                    o.present = true;
                    o.parentId = (keeper == Side.LOCAL ? o.local : o.remote).parentId();
                    o.parentFromLocal = keeper == Side.LOCAL;
                } else {
                    // 被移進回收桶：放回另一邊的位置
                    parentFrom(o, keeper);
                }
                s.notice(MergeNotice.Kind.GROUP_KEPT, o.id);
            }
        }
    }

    private static boolean hasKeptChild(MergeState s, UUID id) {
        for (EntryMerge.Outcome e : s.entries.values()) {
            if (e.present && e.followsDeletionOf == null && id.equals(e.groupId)) {
                return true;
            }
        }
        for (Outcome g : s.groups.values()) {
            if (g.present && !g.followsDeletion() && id.equals(g.parentId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 整理合併後的樹：上層不存在 → 根群組；移動造成循環 → 改回遠端的位置；跟著刪除那一邊的東西若被保留的群組帶出了
     * 回收桶 → 直接放進回收桶。
     */
    static void settle(MergeState s) {
        for (Outcome o : s.groups.values()) {
            if (o.present && !o.id.equals(s.root) && !isPresent(s, o.parentId)) {
                o.parentId = s.root;
                o.parentFromLocal = o.remote == null || !s.root.equals(o.remote.parentId());
                s.notice(MergeNotice.Kind.MOVED_TO_ROOT, o.id);
            }
        }
        breakCycles(s);
        for (Outcome o : s.groups.values()) {
            UUID bin = o.present && o.followsDeletion() ? s.side(o.deletedBy).recycleBinId() : null;
            if (bin != null && isPresent(s, bin) && !inFinalBin(s, o.id, bin) && !o.id.equals(bin)) {
                o.parentId = bin;
                o.parentFromLocal = o.remote == null || !bin.equals(o.remote.parentId());
            }
        }
        for (EntryMerge.Outcome e : s.entries.values()) {
            if (!e.present) {
                continue;
            }
            if (!isPresent(s, e.groupId)) {
                e.groupId = s.root;
                s.notice(MergeNotice.Kind.MOVED_TO_ROOT, e.id);
            }
            UUID bin = e.followsDeletionOf == null ? null : s.side(e.followsDeletionOf).recycleBinId();
            if (bin != null && isPresent(s, bin) && !inFinalBin(s, e.groupId, bin)) {
                e.groupId = bin;
            }
            e.groupFromLocal = e.remote == null || !e.groupId.equals(e.remote.groupId());
        }
    }

    /**
     * 兩邊各自把對方移進自己底下會造成循環。每次切斷一個：循環上第一個取本機位置的群組改回遠端的位置（遠端本身是
     * 一棵樹），沒有遠端位置時移到根群組。每次至少讓一個群組不再取本機位置，所以最多切群組數那麼多次。
     */
    private static void breakCycles(MergeState s) {
        for (int round = 0; round <= s.groups.size(); round++) {
            Outcome onCycle = findCycle(s);
            if (onCycle == null) {
                return;
            }
            Outcome cut = null;
            Outcome c = onCycle;
            do {
                if (c.parentFromLocal && cut == null) {
                    cut = c;
                }
                c = s.groups.get(c.parentId);
            } while (c != onCycle);
            if (cut == null) {
                cut = onCycle;
            }
            UUID remoteParent = cut.remote == null ? null : cut.remote.parentId();
            cut.parentId = cut.parentFromLocal && isPresent(s, remoteParent) ? remoteParent : s.root;
            cut.parentFromLocal = cut.remote == null || !cut.parentId.equals(remoteParent);
            s.notice(MergeNotice.Kind.GROUP_NEWER_KEPT, cut.id);
        }
    }

    /** @return 循環上的某個群組；沒有循環時為 {@code null} */
    private static Outcome findCycle(MergeState s) {
        for (Outcome start : s.groups.values()) {
            Set<UUID> seen = new HashSet<>();
            Outcome g = start;
            while (g != null && g.present && g.parentId != null && seen.add(g.id)) {
                g = s.groups.get(g.parentId);
            }
            if (g != null && g.present && g.parentId != null) {
                return g;
            }
        }
        return null;
    }

    private static boolean inFinalBin(MergeState s, UUID groupId, UUID bin) {
        UUID g = groupId;
        for (int i = 0; g != null && i <= s.groups.size(); i++) {
            if (g.equals(bin)) {
                return true;
            }
            Outcome o = s.groups.get(g);
            g = o == null ? null : o.parentId;
        }
        return false;
    }

    private static boolean isPresent(MergeState s, UUID groupId) {
        Outcome o = groupId == null ? null : s.groups.get(groupId);
        return o != null && o.present;
    }

    private static void parentFrom(Outcome o, Side side) {
        o.parentId = (side == Side.LOCAL ? o.local : o.remote).parentId();
        o.parentFromLocal = side == Side.LOCAL && !Objects.equals(o.local.parentId(), o.remote.parentId());
    }

    private static String str(UUID id) {
        return id == null ? null : id.toString();
    }
}
