package app.svault.android.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜尋結果的關鍵字標示：找出文字中符合關鍵字的範圍（不分大小寫、不重疊）。
 *
 * <p>與 desktop 的 {@code Highlight} 規則相同；兩邊各自一份，因為平台外殼互不依賴、core 不放 UI 的東西。
 */
public final class Highlight {

    /**
     * @param start 開始位置（含）
     * @param end   結束位置（不含）
     */
    public record Range(int start, int end) { }

    private Highlight() {
    }

    /**
     * @param text  要顯示的文字
     * @param query 關鍵字；空白或 {@code null} 時沒有範圍
     * @return 依序排列的符合範圍
     */
    public static List<Range> ranges(String text, String query) {
        List<Range> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        if (text == null || q.isEmpty()) {
            return out;
        }
        int i = 0;
        while (i <= text.length() - q.length()) {
            // regionMatches 逐字比對，不受 toLowerCase 改變字串長度（例如 İ）的影響
            if (text.regionMatches(true, i, q, 0, q.length())) {
                out.add(new Range(i, i + q.length()));
                i += q.length();
            } else {
                i++;
            }
        }
        return List.copyOf(out);
    }
}
