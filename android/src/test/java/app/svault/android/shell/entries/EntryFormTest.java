package app.svault.android.shell.entries;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

/**
 * 編輯畫面的欄位：名稱必填、有沒有修改（離開時「捨棄修改？」）。
 */
public class EntryFormTest {

    private static final Instant T = Instant.parse("2026-10-19T00:00:00Z");
    private final UUID group = UUID.randomUUID();

    @Test
    public void nameIsRequired() {
        assertTrue(new EntryForm("", "a", "", "", "", group).nameMissing());
        assertTrue(new EntryForm("  \t", "a", "", "", "", group).nameMissing());
        assertFalse(new EntryForm("GitHub", "", "", "", "", group).nameMissing());
    }

    @Test
    public void dirtyWhenAnyFieldOrGroupChanges() {
        EntryView e = new EntryView(UUID.randomUUID(), group, "Mail", "me", "pw", "", "n", Map.of(), List.of(), "",
                T, T, 0);
        EntryForm initial = EntryForm.of(e);

        assertFalse(initial.dirtyComparedTo(new EntryForm("Mail", "me", "pw", "", "n", group)));
        assertTrue(initial.dirtyComparedTo(new EntryForm("Mail", "me", "pw2", "", "n", group)));
        assertTrue(initial.dirtyComparedTo(new EntryForm("Mail", "me", "pw", "", "n", UUID.randomUUID())));
        assertTrue(EntryForm.blank(group).dirtyComparedTo(new EntryForm("", "", "", "x", "", group)));
        assertFalse(EntryForm.blank(group).dirtyComparedTo(EntryForm.blank(group)));
    }

    @Test
    public void draftCarriesFiveStandardFields() {
        EntryDraft d = new EntryForm("Mail", "me", "pw", "https://m", "n", group).draft();
        assertEquals(new EntryDraft("Mail", "me", "pw", "https://m", "n"), d);
    }

    @Test
    public void toStringDoesNotLeakContent() {
        String s = new EntryForm("Mail", "me", "secret-pw", "https://m", "n", group).toString();
        assertFalse(s, s.contains("secret-pw") || s.contains("Mail") || s.contains("me"));
    }
}
