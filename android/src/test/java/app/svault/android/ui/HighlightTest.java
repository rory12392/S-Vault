package app.svault.android.ui;

import static org.junit.Assert.assertEquals;

import java.util.List;
import org.junit.Test;

/**
 * 搜尋結果的關鍵字標示（EntryRow 的「搜尋結果」變體）。
 */
public class HighlightTest {

    @Test
    public void marksEveryMatchIgnoringCase() {
        assertEquals(List.of(new Highlight.Range(0, 4), new Highlight.Range(8, 12)),
                Highlight.ranges("Mail 學校 mail", "MAIL"));
    }

    @Test
    public void blankQueryMarksNothing() {
        assertEquals(List.of(), Highlight.ranges("網路銀行", "  "));
        assertEquals(List.of(), Highlight.ranges("網路銀行", null));
        assertEquals(List.of(), Highlight.ranges(null, "銀行"));
    }

    @Test
    public void queryIsTrimmedAndMatchesChinese() {
        assertEquals(List.of(new Highlight.Range(2, 4)), Highlight.ranges("網路銀行", " 銀行 "));
    }

    @Test
    public void matchesDoNotOverlap() {
        assertEquals(List.of(new Highlight.Range(0, 2), new Highlight.Range(2, 4)), Highlight.ranges("aaaaa", "aa"));
    }
}
