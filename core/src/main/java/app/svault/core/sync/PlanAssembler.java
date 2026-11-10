package app.svault.core.sync;

import app.svault.core.vault.GroupSnapshot;
import app.svault.core.vault.MergePlan;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 把條目與群組的決定整理成 {@link MergePlan}（相對於遠端的變更，套用順序見 {@code MergePlan}）。
 */
final class PlanAssembler {

    private PlanAssembler() {
    }

    static MergePlan build(MergeState s) {
        List<MergePlan.GroupChange> groups = new ArrayList<>();
        List<MergePlan.GroupChange> updates = new ArrayList<>();
        for (GroupMerge.Outcome o : preorder(s)) {
            if (o.remote == null) {
                groups.add(new MergePlan.GroupChange(o.id, true, Collections.<String>emptySet(), o.parentId,
                        null, null));
            } else if (!o.fromLocal.isEmpty() || !Objects.equals(o.parentId, o.remote.parentId())) {
                boolean moved = !Objects.equals(o.parentId, o.remote.parentId());
                Instant location = moved && o.local != null && Objects.equals(o.parentId, o.local.parentId())
                        ? o.local.locationChanged() : null;
                updates.add(new MergePlan.GroupChange(o.id, false, o.fromLocal, o.parentId, o.lastModified,
                        location));
            }
        }
        groups.addAll(updates);

        List<MergePlan.EntryChange> entries = new ArrayList<>();
        List<UUID> removedEntries = new ArrayList<>();
        for (EntryMerge.Outcome o : s.entries.values()) {
            if (!o.present) {
                if (o.remote != null) {
                    removedEntries.add(o.id);
                }
                continue;
            }
            if (o.remote == null) {
                entries.add(new MergePlan.EntryChange(o.id, true, Collections.<String>emptySet(), o.groupId, null,
                        null, Collections.<Instant>emptyList(), false, false));
                continue;
            }
            boolean moved = !o.groupId.equals(o.remote.groupId());
            List<Instant> history = o.local == null ? Collections.<Instant>emptyList()
                    : EntryMerge.historyFromLocal(o);
            if (o.fromLocal.isEmpty() && !moved && history.isEmpty() && !o.pushRemote && !o.pushLocal
                    && o.lastModified == null) {
                continue;
            }
            Instant location = moved && o.local != null && o.groupId.equals(o.local.groupId())
                    ? o.local.locationChanged() : null;
            entries.add(new MergePlan.EntryChange(o.id, false, o.fromLocal, o.groupId, o.lastModified, location,
                    history, o.pushRemote, o.pushLocal));
        }

        Map<UUID, Instant> deleted = deletedObjects(s);
        UUID bin = s.newRecycleBin;
        GroupMerge.Outcome binOutcome = bin == null ? null : s.groups.get(bin);
        return new MergePlan(groups, entries, removedEntries, removedGroups(s),
                deleted.equals(s.remote.deleted()) ? null : deleted,
                binOutcome != null && binOutcome.present ? bin : null);
    }

    /** @return 和這台裝置原本的內容不同的條目數（AC-07-5） */
    static int changedForLocal(MergeState s) {
        int n = 0;
        for (EntryMerge.Outcome o : s.entries.values()) {
            boolean inLocal = o.local != null;
            if (o.present != inLocal) {
                n++;
            } else if (o.present && (!o.fromRemoteChanged.isEmpty() || !o.groupId.equals(o.local.groupId()))) {
                n++;
            }
        }
        return n;
    }

    /** 兩邊的刪除紀錄取聯集（同一個 UUID 取較晚的時間），但不含合併後還在的（被保留的，第 4 點）。 */
    private static Map<UUID, Instant> deletedObjects(MergeState s) {
        Map<UUID, Instant> out = new LinkedHashMap<>(s.remote.deleted());
        for (Map.Entry<UUID, Instant> d : s.local.deleted().entrySet()) {
            Instant before = out.get(d.getKey());
            if (before == null || d.getValue().isAfter(before)) {
                out.put(d.getKey(), d.getValue());
            }
        }
        out.keySet().removeIf(id -> present(s, id));
        return out;
    }

    private static boolean present(MergeState s, UUID id) {
        EntryMerge.Outcome e = s.entries.get(id);
        GroupMerge.Outcome g = s.groups.get(id);
        return e != null && e.present || g != null && g.present;
    }

    /** @return 合併後的群組，上層在子群組前面（新增時上層一定已經存在） */
    private static List<GroupMerge.Outcome> preorder(MergeState s) {
        Map<UUID, List<GroupMerge.Outcome>> children = new HashMap<>();
        for (GroupMerge.Outcome o : s.groups.values()) {
            if (o.present && o.parentId != null) {
                children.computeIfAbsent(o.parentId, k -> new ArrayList<>()).add(o);
            }
        }
        List<GroupMerge.Outcome> out = new ArrayList<>();
        GroupMerge.Outcome root = s.groups.get(s.root);
        if (root != null) {
            visit(root, children, out);
        }
        return out;
    }

    private static void visit(GroupMerge.Outcome o, Map<UUID, List<GroupMerge.Outcome>> children,
                              List<GroupMerge.Outcome> out) {
        out.add(o);
        for (GroupMerge.Outcome child : children.getOrDefault(o.id, Collections.<GroupMerge.Outcome>emptyList())) {
            visit(child, children, out);
        }
    }

    /** @return 要從遠端移除的群組，深的在前（子群組先移除） */
    private static List<UUID> removedGroups(MergeState s) {
        List<UUID> out = new ArrayList<>();
        for (GroupMerge.Outcome o : s.groups.values()) {
            if (!o.present && o.remote != null) {
                out.add(o.id);
            }
        }
        out.sort(Comparator.comparingInt((UUID id) -> depth(s.remote.groups(), id)).reversed());
        return out;
    }

    private static int depth(Map<UUID, GroupSnapshot> groups, UUID id) {
        int d = 0;
        GroupSnapshot g = groups.get(id);
        while (g != null && g.parentId() != null && d <= groups.size()) {
            d++;
            g = groups.get(g.parentId());
        }
        return d;
    }
}
