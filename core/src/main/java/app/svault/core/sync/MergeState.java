package app.svault.core.sync;

import app.svault.core.vault.EntrySnapshot;
import app.svault.core.vault.GroupSnapshot;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 一次合併的輸入與進行中的決定（{@link ThreeWayMerger} 內部用；{@link EntryMerge}、{@link GroupMerge}、
 * {@link PlanAssembler} 共用）。
 *
 * <p>資源回收桶：兩邊在上次同步後各自第一次刪除東西時，會各自建立一個回收桶（UUID 不同）。這時把本機的回收桶當成
 * 遠端那一個（本機快照裡的 UUID 換成遠端的），合併結果只有一個回收桶。
 */
final class MergeState {

    /** 欄位層級的決定。 */
    enum Pick {
        /** 兩邊相同，不用改。 */
        SAME,
        /** 取本機的值。 */
        LOCAL,
        /** 取遠端的值（本機要跟著改）。 */
        REMOTE,
        /** 三者都不同。 */
        CONFLICT
    }

    final VaultSnapshot base;
    final VaultSnapshot originalLocal;
    final VaultSnapshot local;
    final VaultSnapshot remote;
    final UUID root;
    /** 本機新建了回收桶、遠端沒有時，合併後要指定的回收桶；否則為 {@code null}。 */
    final UUID newRecycleBin;
    final Map<UUID, Map<String, Side>> choices;
    final Instant resolvedAt;

    final Map<UUID, EntryMerge.Outcome> entries = new LinkedHashMap<>();
    final Map<UUID, GroupMerge.Outcome> groups = new LinkedHashMap<>();
    final List<Conflict> conflicts = new ArrayList<>();
    final List<MergeNotice> notices = new ArrayList<>();

    MergeState(VaultSnapshot base, VaultSnapshot local, VaultSnapshot remote, Map<UUID, Map<String, Side>> choices,
               Instant resolvedAt) {
        this.base = base;
        this.originalLocal = local;
        this.remote = remote;
        this.root = remote.vaultId();
        this.local = sameRecycleBin(base, local, remote);
        this.newRecycleBin = remote.recycleBinId() == null && this.local.recycleBinId() != null
                && (base == null || !this.local.recycleBinId().equals(base.recycleBinId()))
                ? this.local.recycleBinId() : null;
        this.choices = choices;
        this.resolvedAt = resolvedAt == null ? null : resolvedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    boolean hasBase() {
        return base != null;
    }

    VaultSnapshot side(Side side) {
        return side == Side.LOCAL ? local : remote;
    }

    static Side other(Side side) {
        return side == Side.LOCAL ? Side.REMOTE : Side.LOCAL;
    }

    /**
     * 逐欄規則（Architecture §4 第 2 點）：L＝R → 不用改；L＝B → 取 R；R＝B → 取 L；三者都不同 → 衝突。
     * 沒有基準的值（這個項目基準裡沒有、或整次合併沒有基準）時取 {@code newer}，不產生衝突（第 6 點）。
     */
    static Pick pick(boolean hasBase, String b, String l, String r, Side newer) {
        if (Objects.equals(l, r)) {
            return Pick.SAME;
        }
        if (!hasBase) {
            return newer == Side.LOCAL ? Pick.LOCAL : Pick.REMOTE;
        }
        if (Objects.equals(l, b)) {
            return Pick.REMOTE;
        }
        if (Objects.equals(r, b)) {
            return Pick.LOCAL;
        }
        return Pick.CONFLICT;
    }

    /** @return 較新的一邊；同時（或都沒有時間）算遠端：遠端是檔案裡已經有的內容 */
    static Side newer(Instant local, Instant remote) {
        return local.isAfter(remote) ? Side.LOCAL : Side.REMOTE;
    }

    static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }

    /** @return 條目在上次同步後被這一邊刪除：整筆不見了，或從回收桶外移進了回收桶 */
    boolean entryDeletedOn(Side side, UUID id) {
        EntrySnapshot b = base == null ? null : base.entries().get(id);
        if (b == null || base.inRecycleBin(b.groupId())) {
            return false;
        }
        EntrySnapshot s = side(side).entries().get(id);
        return s == null || side(side).inRecycleBin(s.groupId());
    }

    /** @return 群組在上次同步後被這一邊刪除（同條目；回收桶本身與根群組不算） */
    boolean groupDeletedOn(Side side, UUID id) {
        GroupSnapshot b = base == null ? null : base.groups().get(id);
        if (b == null || id.equals(root) || id.equals(base.recycleBinId()) || base.inRecycleBin(id)) {
            return false;
        }
        GroupSnapshot s = side(side).groups().get(id);
        return s == null || side(side).inRecycleBin(id);
    }

    /**
     * @return 這一邊相對基準有修改（內容不同，或移到回收桶以外的地方）；移進回收桶是刪除，不算修改
     */
    boolean entryModifiedOn(Side side, UUID id) {
        EntrySnapshot b = base == null ? null : base.entries().get(id);
        EntrySnapshot s = side(side).entries().get(id);
        if (b == null || s == null) {
            return false;
        }
        return !s.fields().equals(b.fields())
                || !s.groupId().equals(b.groupId()) && !side(side).inRecycleBin(s.groupId());
    }

    /** @return 群組這一邊相對基準有修改（同條目） */
    boolean groupModifiedOn(Side side, UUID id) {
        GroupSnapshot b = base == null ? null : base.groups().get(id);
        GroupSnapshot s = side(side).groups().get(id);
        if (b == null || s == null) {
            return false;
        }
        return !s.fields().equals(b.fields())
                || !Objects.equals(s.parentId(), b.parentId()) && !side(side).inRecycleBin(id);
    }

    void notice(MergeNotice.Kind kind, UUID id) {
        notices.add(new MergeNotice(kind, id));
    }

    /** 兩邊各自新建的回收桶視為同一個：本機快照裡的回收桶 UUID 換成遠端的。 */
    private static VaultSnapshot sameRecycleBin(VaultSnapshot base, VaultSnapshot local, VaultSnapshot remote) {
        UUID mine = local.recycleBinId();
        UUID theirs = remote.recycleBinId();
        if (mine == null || theirs == null || mine.equals(theirs)
                || base != null && base.groups().containsKey(mine) || remote.groups().containsKey(mine)) {
            return local;
        }
        Map<UUID, GroupSnapshot> groups = new LinkedHashMap<>();
        for (GroupSnapshot g : local.groups().values()) {
            if (!g.uuid().equals(mine)) {
                groups.put(g.uuid(), new GroupSnapshot(g.uuid(), mine.equals(g.parentId()) ? theirs : g.parentId(),
                        g.fields(), g.lastModified(), g.locationChanged()));
            }
        }
        Map<UUID, EntrySnapshot> entries = new LinkedHashMap<>();
        for (EntrySnapshot e : local.entries().values()) {
            entries.put(e.uuid(), e.groupId().equals(mine)
                    ? new EntrySnapshot(e.uuid(), theirs, e.fields(), e.protectedKeys(), e.lastModified(),
                            e.locationChanged(), e.historyTimes())
                    : e);
        }
        if (!groups.containsKey(theirs)) {
            // 遠端的回收桶本機沒有：放一份遠端的進本機快照，本機回收桶裡的東西才有地方放
            groups.put(theirs, remote.groups().get(theirs));
        }
        return new VaultSnapshot(local.vaultId(), entries, groups, local.deleted(), theirs);
    }
}
