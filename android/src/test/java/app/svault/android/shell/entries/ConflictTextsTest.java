package app.svault.android.shell.entries;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.svault.core.sync.ConflictView;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

/** 衝突對照的文字對應（T-16）：每一種欄位與值的說法都有文字，只有值本身會遮罩。 */
public class ConflictTextsTest {

    @Test
    public void everyKindHasItsOwnName() {
        Set<Integer> seen = new HashSet<>();
        for (ConflictView.Kind kind : ConflictView.Kind.values()) {
            int res = ConflictTexts.field(kind);
            assertNotEquals(kind.name(), 0, res);
            assertTrue(kind.name() + " 和別的種類用同一個字串", seen.add(res));
        }
    }

    @Test
    public void everyFormHasAText() {
        Set<ConflictView.Shown.Form> direct = EnumSet.of(ConflictView.Shown.Form.TEXT,
                ConflictView.Shown.Form.GROUP, ConflictView.Shown.Form.EMPTY);
        for (ConflictView.Shown.Form form : ConflictView.Shown.Form.values()) {
            if (direct.contains(form)) {
                assertEquals(form.name() + " 直接顯示值", 0, ConflictTexts.value(form));
            } else {
                assertNotEquals(form.name(), 0, ConflictTexts.value(form));
            }
        }
    }

    @Test
    public void onlyValuesAreMaskable() {
        for (ConflictView.Shown.Form form : ConflictView.Shown.Form.values()) {
            if (form == ConflictView.Shown.Form.TEXT) {
                assertTrue(ConflictTexts.maskable(form));
            } else {
                assertFalse(form.name() + "：說法照常顯示，遮罩時也看得出一邊刪掉了", ConflictTexts.maskable(form));
            }
        }
    }
}
