package app.svault.core.feature.importer;

import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 的記錄切分：逗號分隔、欄位可以用雙引號包住（裡面可有逗號、換行，引號寫兩次代表一個引號）。
 * 換行接受 CRLF、LF、CR；引號內的換行原樣保留在欄位值裡。
 *
 * <p>比 RFC 寬鬆的地方：沒有引號的欄位中間出現的引號當成一般字元（不丟掉使用者的資料）。
 * 自己寫而不加套件：工作卡規定不加依賴，而且格式只有這一種。
 */
final class CsvRecords {

    /**
     * 一列最多幾個欄位：Chrome 匯出只有 5 欄，留足多的欄位。超過時邊讀邊停、整列當成欄位數錯誤，
     * 不先把幾百萬個空欄位放進清單（C-7）。
     */
    static final int MAX_FIELDS = 64;

    /**
     * 一筆記錄。
     *
     * @param line   開始的行號（從 1 起算）
     * @param fields 欄位值；有錯誤時為 {@code null}
     * @param error  格式錯誤；正常時為 {@code null}
     */
    record Record(int line, List<String> fields, SkippedLine.Reason error) {

        /** 空白行：只有一個空欄位。 */
        boolean isBlankLine() {
            return fields != null && fields.size() == 1 && fields.get(0).isEmpty();
        }
    }

    private final CharSequence text;
    private int pos;
    private int line = 1;

    /**
     * @param text 整個檔案（已去除 BOM）
     */
    CsvRecords(CharSequence text) {
        this.text = text;
    }

    /**
     * @return 下一筆記錄；到檔尾時為 {@code null}
     */
    Record next() {
        int len = text.length();
        if (pos >= len) {
            return null;
        }
        int start = line;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        while (true) {
            if (pos < len && text.charAt(pos) == '"') {
                pos++;
                if (!readQuoted(field)) {
                    // 之後的內容都在這個沒關閉的引號裡，沒辦法判斷欄位邊界
                    pos = len;
                    return new Record(start, null, SkippedLine.Reason.UNCLOSED_QUOTE);
                }
                if (pos < len && !isDelimiter(text.charAt(pos))) {
                    skipLine();
                    return new Record(start, null, SkippedLine.Reason.TEXT_AFTER_QUOTE);
                }
            } else {
                while (pos < len && !isDelimiter(text.charAt(pos))) {
                    field.append(text.charAt(pos++));
                }
            }
            fields.add(field.toString());
            field.setLength(0);
            if (pos >= len) {
                return new Record(start, fields, null);
            }
            if (text.charAt(pos) == ',') {
                pos++;
                if (fields.size() >= MAX_FIELDS) {
                    skipLine();
                    return new Record(start, null, SkippedLine.Reason.FIELD_COUNT);
                }
                continue;
            }
            consumeLineBreak();
            return new Record(start, fields, null);
        }
    }

    /** 讀到關閉的引號為止（{@link #pos} 停在關閉引號的下一個字元）；檔尾前沒關閉時回傳 {@code false}。 */
    private boolean readQuoted(StringBuilder field) {
        int len = text.length();
        while (pos < len) {
            char c = text.charAt(pos);
            if (c == '"') {
                if (pos + 1 < len && text.charAt(pos + 1) == '"') {
                    field.append('"');
                    pos += 2;
                    continue;
                }
                pos++;
                return true;
            }
            if (c == '\r' || c == '\n') {
                int from = pos;
                consumeLineBreak();
                field.append(text, from, pos);
                continue;
            }
            field.append(c);
            pos++;
        }
        return false;
    }

    /** 格式錯誤後跳到這一行結束（不理會引號），從下一行重新開始。 */
    private void skipLine() {
        int len = text.length();
        while (pos < len && text.charAt(pos) != '\r' && text.charAt(pos) != '\n') {
            pos++;
        }
        if (pos < len) {
            consumeLineBreak();
        }
    }

    private void consumeLineBreak() {
        if (text.charAt(pos) == '\r' && pos + 1 < text.length() && text.charAt(pos + 1) == '\n') {
            pos++;
        }
        pos++;
        line++;
    }

    private static boolean isDelimiter(char c) {
        return c == ',' || c == '\r' || c == '\n';
    }
}
