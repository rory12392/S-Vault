package app.svault.desktop.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜尋結果的關鍵字標示：把文字切成「符合／不符合」片段（不分大小寫）。
 */
public final class Highlight {

    /**
     * @param text  片段文字
     * @param match 是否為符合關鍵字的部分
     */
    public record Segment(String text, boolean match) { }

    private Highlight() {
    }

    /**
     * @param text  要顯示的文字
     * @param query 關鍵字；空白或 {@code null} 時整段不標示
     * @return 依序排列的片段（合起來等於 {@code text}）
     */
    public static List<Segment> split(String text, String query) {
        List<Segment> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            out.add(new Segment(text, false));
            return out;
        }
        int start = 0;
        int i = 0;
        while (i <= text.length() - q.length()) {
            // regionMatches 逐字比對，不受 toLowerCase 改變字串長度（例如 İ）的影響
            if (text.regionMatches(true, i, q, 0, q.length())) {
                if (i > start) {
                    out.add(new Segment(text.substring(start, i), false));
                }
                out.add(new Segment(text.substring(i, i + q.length()), true));
                i += q.length();
                start = i;
            } else {
                i++;
            }
        }
        if (start < text.length()) {
            out.add(new Segment(text.substring(start), false));
        }
        return List.copyOf(out);
    }
}
