package app.svault.core.feature.importer;

/**
 * 格式錯誤而跳過的一列（AC-14-2：列出行號）。
 *
 * @param line   這一列在檔案中開始的行號（從 1 起算，標題列是第 1 行；欄位內有換行時一列會跨好幾行）
 * @param reason 跳過的原因
 */
public record SkippedLine(int line, Reason reason) {

    /** 跳過的原因；平台外殼依此顯示繁體中文說明。 */
    public enum Reason {
        /** 欄位數和標題列不同。 */
        FIELD_COUNT,
        /** 引號沒有關閉：從這一列到檔尾都無法判斷欄位邊界，全部跳過。 */
        UNCLOSED_QUOTE,
        /** 引號關閉後、逗號或換行前還有其他字元（RFC 4180 不允許）。 */
        TEXT_AFTER_QUOTE,
        /** 名稱、網址、帳號、密碼、備註全部空白。 */
        EMPTY_ENTRY,
    }
}
