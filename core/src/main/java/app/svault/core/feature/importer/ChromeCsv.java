package app.svault.core.feature.importer;

import java.util.List;

/**
 * Chrome 密碼 CSV 的解析結果（{@link ChromeCsvParser}）。
 *
 * @param rows    可以匯入的列，依檔案順序
 * @param skipped 格式錯誤而跳過的列，依行號順序（AC-14-2）
 */
public record ChromeCsv(List<ChromeCsvRow> rows, List<SkippedLine> skipped) {

    /**
     * 複製成不可修改的清單。
     */
    public ChromeCsv {
        rows = List.copyOf(rows);
        skipped = List.copyOf(skipped);
    }

    /**
     * 只輸出筆數，不含內容（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "ChromeCsv[rows=" + rows.size() + ", skipped=" + skipped.size() + "]";
    }
}
