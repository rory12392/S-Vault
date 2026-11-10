package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code EntryNavigator} 的判斷（T-30，Architecture §6）：一般條目、回收桶裡的條目、找不到、沒有內容（上鎖或還在載入）。
 */
class EntryTargetTest {

    private static final Instant T = Instant.parse("2026-10-22T00:00:00Z");
    private static final UUID ROOT = UUID.randomUUID();
    private static final UUID WORK = UUID.randomUUID();
    private static final UUID BIN = UUID.randomUUID();
    private static final UUID OLD_GROUP = UUID.randomUUID();

    private static EntryView entry(UUID group) {
        return new EntryView(UUID.randomUUID(), group, "名稱", "", "", "", "", Map.of(), List.of(), "", T, T, 0);
    }

    private static ScreenData data(List<EntryView> entries) {
        return ScreenData.of(entries, List.of(
                new GroupView(ROOT, null, "根", "", false, T),
                new GroupView(WORK, ROOT, "工作", "", false, T),
                new GroupView(BIN, ROOT, "資源回收桶", "", true, T),
                new GroupView(OLD_GROUP, BIN, "刪除的群組", "", false, T)));
    }

    @Test
    @DisplayName("一般群組與根群組的條目：ACTIVE，帶著條目")
    void active() {
        EntryView inWork = entry(WORK);
        EntryView inRoot = entry(ROOT);
        ScreenData d = data(List.of(inWork, inRoot));
        EntryTarget t = EntryTarget.resolve(d, inWork.uuid());
        assertEquals(EntryTarget.Kind.ACTIVE, t.kind());
        assertSame(inWork, t.entry());
        assertEquals(EntryTarget.Kind.ACTIVE, EntryTarget.resolve(d, inRoot.uuid()).kind());
        assertTrue(t.opens());
    }

    @Test
    @DisplayName("資源回收桶與回收桶裡的群組中的條目：RECYCLED，一樣開詳細")
    void recycled() {
        EntryView inBin = entry(BIN);
        EntryView inOldGroup = entry(OLD_GROUP);
        ScreenData d = data(List.of(inBin, inOldGroup));
        assertEquals(EntryTarget.Kind.RECYCLED, EntryTarget.resolve(d, inBin.uuid()).kind());
        EntryTarget nested = EntryTarget.resolve(d, inOldGroup.uuid());
        assertEquals(EntryTarget.Kind.RECYCLED, nested.kind());
        assertSame(inOldGroup, nested.entry());
        assertTrue(nested.opens());
    }

    @Test
    @DisplayName("不在保管庫裡的 UUID 與 null：NOT_FOUND，不丟例外")
    void notFound() {
        ScreenData d = data(List.of(entry(WORK)));
        EntryTarget t = EntryTarget.resolve(d, UUID.randomUUID());
        assertEquals(EntryTarget.Kind.NOT_FOUND, t.kind());
        assertNull(t.entry());
        assertEquals(EntryTarget.Kind.NOT_FOUND, EntryTarget.resolve(d, null).kind());
        assertEquals(false, t.opens());
    }

    @Test
    @DisplayName("沒有內容（已上鎖、還在載入）：UNAVAILABLE")
    void unavailable() {
        EntryTarget t = EntryTarget.resolve(null, UUID.randomUUID());
        assertEquals(EntryTarget.Kind.UNAVAILABLE, t.kind());
        assertNull(t.entry());
        assertEquals(false, t.opens());
    }

    @Test
    @DisplayName("toString 只有種類與 UUID，不含條目內容")
    void toStringHasNoContent() {
        EntryView e = entry(WORK);
        String s = EntryTarget.resolve(data(List.of(e)), e.uuid()).toString();
        assertTrue(s.contains("ACTIVE") && s.contains(e.uuid().toString()), s);
        assertEquals(false, s.contains("名稱"), s);
    }
}
