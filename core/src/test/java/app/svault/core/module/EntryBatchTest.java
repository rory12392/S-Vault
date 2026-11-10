package app.svault.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.EntryDraft;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 模組寫入的一批條目與結果（T-33）：建立時就檢查（不合格的不會排進保管庫執行緒）、不可修改、{@code toString()} 不含內容。
 */
class EntryBatchTest {

    private static EntryDraft draft(String title) {
        return new EntryDraft(title, "user", "probe1234", "https://example.com", "備註");
    }

    @Test
    @DisplayName("T-33：群組名稱去掉前後空白；清單是複本、不可修改")
    void normalizesAndCopies() {
        List<EntryDraft> source = new ArrayList<>(List.of(draft("A"), draft("B")));

        EntryBatch batch = new EntryBatch("  Chrome 匯入 ", source);
        source.clear();

        assertEquals("Chrome 匯入", batch.groupName());
        assertEquals(2, batch.size());
        assertThrows(UnsupportedOperationException.class, () -> batch.entries().add(draft("C")));
    }

    @Test
    @DisplayName("T-33：群組名稱空白、沒有條目、超過上限、有條目名稱空白、null → 建立時就拒絕")
    void rejectsInvalidBatches() {
        assertThrows(IllegalArgumentException.class, () -> new EntryBatch(" ", List.of(draft("A"))));
        assertThrows(IllegalArgumentException.class, () -> new EntryBatch("G", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new EntryBatch("G", Collections.nCopies(EntryBatch.MAX_ENTRIES + 1, draft("A"))));
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new EntryBatch("G", List.of(draft("A"), draft("\t"))));
        assertEquals("第 2 筆條目的名稱是空的", blank.getMessage());
        assertThrows(NullPointerException.class, () -> new EntryBatch(null, List.of(draft("A"))));
        List<EntryDraft> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(NullPointerException.class, () -> new EntryBatch("G", withNull));
        assertEquals(EntryBatch.MAX_ENTRIES,
                new EntryBatch("G", Collections.nCopies(EntryBatch.MAX_ENTRIES, draft("A"))).size());
    }

    @Test
    @DisplayName("T-33：toString() 只有筆數，不含群組名稱、帳號、密碼（CONVENTIONS §2）")
    void toStringHidesContent() {
        String text = new EntryBatch("我的群組", List.of(draft("網站"))).toString();

        assertEquals("EntryBatch[1 筆]", text);
        assertFalse(text.contains("probe1234"));
        assertFalse(text.contains("我的群組"));
    }

    @Test
    @DisplayName("T-33：結果——只有 SAVED 帶群組與筆數，其他一筆都沒加入")
    void results() {
        UUID g = UUID.randomUUID();
        EntryBatchResult ok = EntryBatchResult.saved(g, 3, true);
        assertTrue(ok.saved());
        assertEquals(g, ok.groupId());

        for (EntryBatchResult.Status s : new EntryBatchResult.Status[] {EntryBatchResult.Status.LOCKED,
                EntryBatchResult.Status.CONFLICT, EntryBatchResult.Status.FAILED}) {
            EntryBatchResult r = EntryBatchResult.rejected(s);
            assertFalse(r.saved());
            assertNull(r.groupId());
            assertEquals(0, r.added());
            assertFalse(r.merged());
        }
        assertThrows(IllegalArgumentException.class, () -> EntryBatchResult.rejected(EntryBatchResult.Status.SAVED));
    }
}
