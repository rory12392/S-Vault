package app.svault.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.desktop.ui.Highlight.Segment;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HighlightTest {

    @Test
    @DisplayName("搜尋結果標示關鍵字：不分大小寫、每個出現的位置都標示，片段合起來等於原文")
    void marksEveryMatch() {
        List<Segment> s = Highlight.split("Google Go", "go");
        assertEquals(List.of(new Segment("Go", true), new Segment("ogle ", false), new Segment("Go", true)), s);
    }

    @Test
    @DisplayName("沒有關鍵字或找不到時整段不標示；空字串沒有片段")
    void noQuery() {
        assertEquals(List.of(new Segment("GitHub", false)), Highlight.split("GitHub", ""));
        assertEquals(List.of(new Segment("GitHub", false)), Highlight.split("GitHub", "  "));
        assertEquals(List.of(new Segment("GitHub", false)), Highlight.split("GitHub", "zz"));
        assertTrue(Highlight.split("", "a").isEmpty());
    }

    @Test
    @DisplayName("中文關鍵字與前後空白")
    void chinese() {
        assertEquals(List.of(new Segment("網路", false), new Segment("銀行", true)), Highlight.split("網路銀行", " 銀行 "));
    }
}
