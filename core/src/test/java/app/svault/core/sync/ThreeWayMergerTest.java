package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.EntrySnapshot;
import app.svault.core.vault.GroupSnapshot;
import app.svault.core.vault.MergePlan;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 直接以快照測合併的邊界情況（不經過 KDBX；保管庫層級的案例在 {@code ThreeWayMergerCasesTest}）。
 */
class ThreeWayMergerTest {

    private static final Instant T0 = Instant.parse("2026-10-20T01:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant T2 = T0.plusSeconds(120);

    private final UUID root = UUID.randomUUID();
    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID entry = UUID.randomUUID();

    @Test
    @DisplayName("本機與遠端不是同一個保管庫時拒絕合併")
    void rejectsDifferentVaults() {
        VaultSnapshot mine = vault(root, groups(group(root, null, T0)), Map.of());
        VaultSnapshot other = vault(UUID.randomUUID(), Map.of(), Map.of());
        assertThrows(IllegalArgumentException.class, () -> ThreeWayMerger.merge(null, mine, other));
    }

    @Test
    @DisplayName("第 6 點：基準的保管庫 ID 不符時當成沒有基準（以較新的為準、通知第一次同步）")
    void baseOfAnotherVaultIsIgnored() {
        Map<UUID, GroupSnapshot> g = groups(group(root, null, T0));
        VaultSnapshot other = vault(UUID.randomUUID(), Map.of(), Map.of());
        VaultSnapshot local = vault(root, g, entries(entry(entry, root, "本機", T1)));
        VaultSnapshot remote = vault(root, g, entries(entry(entry, root, "遠端", T2)));

        MergeResult r = ThreeWayMerger.merge(other, local, remote);

        assertFalse(r.hasConflicts());
        assertEquals(MergeNotice.Kind.FIRST_SYNC, r.notices().get(0).kind());
        assertTrue(r.plan().entries().get(0).pushLocalCurrent(), "較舊的本機版本推入歷史");
    }

    @Test
    @DisplayName("AC-08-2：有衝突的欄位沒有選擇時拒絕，結果不變")
    void resolveRequiresEveryField() {
        Map<UUID, GroupSnapshot> g = groups(group(root, null, T0));
        VaultSnapshot base = vault(root, g, entries(entry(entry, root, "原本", T0)));
        VaultSnapshot local = vault(root, g, entries(entry(entry, root, "本機", T1)));
        VaultSnapshot remote = vault(root, g, entries(entry(entry, root, "遠端", T2)));
        MergeResult r = ThreeWayMerger.merge(base, local, remote);
        assertTrue(r.hasConflicts());

        assertThrows(IllegalArgumentException.class, () -> r.resolve(Map.of(), T2));
        assertThrows(IllegalArgumentException.class,
                () -> r.resolve(Map.of(entry, Map.of("s:Notes", Side.LOCAL)), T2));
        assertTrue(r.hasConflicts());

        MergeResult resolved = r.resolve(Map.of(entry, r.conflicts().get(0).choose(Side.LOCAL)), T2.plusMillis(700));
        MergePlan.EntryChange c = resolved.plan().entries().get(0);
        assertEquals(Set.of(EntrySnapshot.stringKey("Password")), c.fieldsFromLocal());
        assertEquals(T2, c.lastModified(), "解決的時間截到秒");
        assertTrue(c.pushRemoteCurrent());
        assertFalse(c.pushLocalCurrent());
    }

    @Test
    @DisplayName("群組：兩邊各自把對方移進自己底下（循環）→ 改回一邊，合併後仍是一棵樹並通知")
    void breaksMoveCycles() {
        VaultSnapshot base = vault(root, groups(group(root, null, T0), group(a, root, T0), group(b, root, T0)),
                Map.of());
        VaultSnapshot local = vault(root, groups(group(root, null, T0), group(b, root, T0), group(a, b, T1)),
                Map.of());
        VaultSnapshot remote = vault(root, groups(group(root, null, T0), group(a, root, T0), group(b, a, T2)),
                Map.of());

        MergeResult r = ThreeWayMerger.merge(base, local, remote);

        Map<UUID, UUID> parents = new HashMap<>();
        for (GroupSnapshot g : remote.groups().values()) {
            parents.put(g.uuid(), g.parentId());
        }
        for (MergePlan.GroupChange c : r.plan().groups()) {
            parents.put(c.uuid(), c.parentId());
        }
        for (UUID start : List.of(a, b)) {
            Set<UUID> seen = new HashSet<>();
            for (UUID g = start; g != null; g = parents.get(g)) {
                assertTrue(seen.add(g), "合併後的群組不能有循環");
            }
            assertTrue(seen.contains(root), "每個群組都接到根群組");
        }
        assertTrue(r.notices().stream().anyMatch(n -> n.kind() == MergeNotice.Kind.GROUP_NEWER_KEPT));
    }

    @Test
    @DisplayName("AC-07-9：基準裡的條目在遠端不見了、也沒有刪除紀錄 → 遠端被取代，改用沒有基準的規則並通知")
    void missingWithoutDeletionRecordMeansReplaced() {
        Map<UUID, GroupSnapshot> g = groups(group(root, null, T0));
        VaultSnapshot base = vault(root, g, entries(entry(entry, root, "原本", T0)));
        VaultSnapshot remote = vault(root, g, Map.of());

        MergeResult r = ThreeWayMerger.merge(base, base, remote);

        assertEquals(MergeNotice.Kind.REMOTE_REPLACED, r.notices().get(0).kind());
        assertTrue(r.plan().entries().get(0).add(), "這台的條目加回遠端");
    }

    @Test
    @DisplayName("AC-07-4：遠端不見的條目記在 DeletedObjects → 照常是遠端刪除（不是被取代）")
    void missingWithDeletionRecordIsADeletion() {
        Map<UUID, GroupSnapshot> g = groups(group(root, null, T0));
        VaultSnapshot base = vault(root, g, entries(entry(entry, root, "原本", T0)));
        VaultSnapshot remote = new VaultSnapshot(root, Map.of(), g, Map.of(entry, T1), null);

        MergeResult r = ThreeWayMerger.merge(base, base, remote);

        assertTrue(r.notices().isEmpty());
        assertTrue(r.plan().isEmpty(), "跟著刪除，遠端不用改");
    }

    @Test
    @DisplayName("AC-07-9：遠端的條目內容和基準不同、歷史紀錄裡卻沒有基準的版本 → 被取代，這台的版本不被改回")
    void changedWithoutBaseVersionInHistoryMeansReplaced() {
        Map<UUID, GroupSnapshot> g = groups(group(root, null, T0));
        VaultSnapshot base = vault(root, g, entries(entry(entry, root, "這台改的", T2)));
        VaultSnapshot replaced = vault(root, g, entries(entry(entry, root, "原本", T0)));
        VaultSnapshot descendant = vault(root, g,
                entries(new EntrySnapshot(entry, root, entry(entry, root, "另一台改的", T2).fields(), Set.of(),
                        T2.plusSeconds(60), T0, List.of(T0, T2))));

        MergeResult r = ThreeWayMerger.merge(base, base, replaced);
        assertEquals(MergeNotice.Kind.REMOTE_REPLACED, r.notices().get(0).kind());
        assertEquals(Set.of(EntrySnapshot.stringKey("Password")), r.plan().entries().get(0).fieldsFromLocal(),
                "較新的這台版本為準");

        MergeResult normal = ThreeWayMerger.merge(base, base, descendant);
        assertTrue(normal.notices().isEmpty(), "歷史紀錄有基準的版本：照常三方合併，取遠端的修改");
        assertTrue(normal.plan().isEmpty());
        assertEquals(1, normal.changedEntries());
    }

    @Test
    @DisplayName("AC-07-9：只有群組不見、沒有刪除紀錄也算被取代；群組有刪除紀錄就是刪除")
    void missingGroupWithoutDeletionRecordMeansReplaced() {
        VaultSnapshot base = vault(root, groups(group(root, null, T0), group(a, root, T0)), Map.of());
        VaultSnapshot remote = vault(root, groups(group(root, null, T0)), Map.of());
        VaultSnapshot deleted = new VaultSnapshot(root, Map.of(), groups(group(root, null, T0)), Map.of(a, T1), null);

        assertEquals(MergeNotice.Kind.REMOTE_REPLACED, ThreeWayMerger.merge(base, base, remote).notices().get(0).kind());
        assertTrue(ThreeWayMerger.merge(base, base, deleted).plan().isEmpty());
    }

    @Test
    @DisplayName("沒有推入歷史紀錄的修改（關掉歷史的工具）當成被取代：較新的為準、較舊的推入歷史，不遺失")
    void editWithoutHistoryFallsBackWithoutLoss() {
        Map<UUID, GroupSnapshot> g = groups(group(root, null, T0));
        VaultSnapshot base = vault(root, g, entries(entry(entry, root, "原本", T0)));
        VaultSnapshot remote = vault(root, g, entries(withoutHistory(entry(entry, root, "遠端", T2))));

        MergeResult r = ThreeWayMerger.merge(base, base, remote);

        assertEquals(MergeNotice.Kind.REMOTE_REPLACED, r.notices().get(0).kind());
        assertTrue(r.plan().entries().get(0).pushLocalCurrent(), "這台較舊的版本推入歷史");
    }

    @Test
    @DisplayName("快照、衝突與結果的 toString 不含條目內容（CONVENTIONS §2）")
    void toStringHidesContent() {
        Map<UUID, GroupSnapshot> g = groups(group(root, null, T0));
        VaultSnapshot base = vault(root, g, entries(entry(entry, root, "原本的祕密", T0)));
        VaultSnapshot local = vault(root, g, entries(entry(entry, root, "本機的祕密", T1)));
        VaultSnapshot remote = vault(root, g, entries(entry(entry, root, "遠端的祕密", T2)));
        MergeResult r = ThreeWayMerger.merge(base, local, remote);

        String all = r + " " + r.conflicts() + " " + r.conflicts().get(0).fields() + " "
                + local.entries().values() + " " + local;
        assertFalse(all.contains("祕密"), all);
        assertFalse(all.contains("Password"), all);
    }

    // 在 T0 之後改過的，T0 的版本在歷史紀錄裡（KDBX 工具修改時都會推入，RemoteLineage 靠它判斷遠端是不是延伸）
    private static EntrySnapshot entry(UUID id, UUID group, String password, Instant modified) {
        Map<String, String> fields = new HashMap<>();
        fields.put(EntrySnapshot.stringKey("Title"), "條目");
        fields.put(EntrySnapshot.stringKey("Password"), password);
        return new EntrySnapshot(id, group, fields, Set.of(), modified, T0,
                modified.equals(T0) ? List.of() : List.of(T0));
    }

    private static EntrySnapshot withoutHistory(EntrySnapshot e) {
        return new EntrySnapshot(e.uuid(), e.groupId(), e.fields(), e.protectedKeys(), e.lastModified(),
                e.locationChanged(), List.of());
    }

    private static GroupSnapshot group(UUID id, UUID parent, Instant moved) {
        return new GroupSnapshot(id, parent, Map.of(GroupSnapshot.NAME, "群組"), T0, moved);
    }

    private static Map<UUID, GroupSnapshot> groups(GroupSnapshot... list) {
        Map<UUID, GroupSnapshot> out = new LinkedHashMap<>();
        for (GroupSnapshot g : list) {
            out.put(g.uuid(), g);
        }
        return out;
    }

    private static Map<UUID, EntrySnapshot> entries(EntrySnapshot... list) {
        Map<UUID, EntrySnapshot> out = new LinkedHashMap<>();
        for (EntrySnapshot e : list) {
            out.put(e.uuid(), e);
        }
        return out;
    }

    private static VaultSnapshot vault(UUID id, Map<UUID, GroupSnapshot> groups, Map<UUID, EntrySnapshot> entries) {
        return new VaultSnapshot(id, entries, groups, Map.of(), null);
    }
}
