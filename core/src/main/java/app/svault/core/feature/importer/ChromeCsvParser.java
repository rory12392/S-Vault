package app.svault.core.feature.importer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 解析 Chrome／Google 密碼管理工具匯出的 CSV（US-14）。
 *
 * <p>欄位以標題列辨識（目前 Chrome 匯出為 {@code name,url,username,password,note}，順序不寫死、名稱不分大小寫，
 * 多的欄位忽略）；{@code url}、{@code username}、{@code password} 必須有，{@code name}、{@code note} 可以沒有。
 * 編碼為 UTF-8，開頭的 BOM 去除。格式錯誤的列跳過並以 {@link SkippedLine} 回報行號（AC-14-2）。
 *
 * <p>明文只在記憶體：不寫暫存檔、不記日誌；讀進來的位元組與解碼用的字元陣列用完清零（CONVENTIONS §2）。
 * 解析出的字串無法清除，由畫面在匯入完成或取消時丟棄參照。
 */
public final class ChromeCsvParser {

    /** 檔案大小上限（16 MiB）：數萬筆密碼也遠低於這個大小，超過多半是選錯檔案。 */
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    /**
     * 記錄數上限（含跳過的列）：16 MiB 裡可以塞進幾百萬筆極短的列，每筆都要建物件；
     * 真正的密碼匯出不會超過幾千筆，超過就當成不是匯出檔（C-7）。
     */
    public static final int MAX_RECORDS = 100_000;

    /** UTF-8 的 BOM（U+FEFF）；寫成數值，原始碼裡不放這個看不見的字元（Android Lint 的 ByteOrderMark）。 */
    private static final char BOM = (char) 0xFEFF;

    private static final String[] COLUMNS = {"name", "url", "username", "password", "note"};
    private static final int NAME = 0;
    private static final int URL = 1;
    private static final int USERNAME = 2;
    private static final int PASSWORD = 3;
    private static final int NOTE = 4;

    private ChromeCsvParser() {
    }

    /**
     * 讀完整個串流再解析。串流由呼叫端關閉。
     *
     * @param in 檔案內容（desktop 的 {@code FileInputStream}、android 的 {@code ContentResolver#openInputStream}）
     * @return 解析結果
     * @throws IOException         讀取失敗
     * @throws ChromeCsvException 整個檔案無法匯入（空檔、缺少欄位、不是 UTF-8、太大）
     */
    public static ChromeCsv read(InputStream in) throws IOException, ChromeCsvException {
        byte[] buf = new byte[8192];
        int length = 0;
        try {
            int n;
            while ((n = in.read(buf, length, buf.length - length)) >= 0) {
                length += n;
                if (length > MAX_BYTES) {
                    throw new ChromeCsvException(ChromeCsvException.Kind.TOO_LARGE, "檔案超過 " + MAX_BYTES + " 位元組");
                }
                if (length == buf.length) {
                    // 自己擴充陣列而不用 ByteArrayOutputStream：舊的陣列才能逐一清零
                    byte[] bigger = Arrays.copyOf(buf, Math.min(buf.length * 2, MAX_BYTES + 1));
                    Arrays.fill(buf, (byte) 0);
                    buf = bigger;
                }
            }
            return parse(buf, length);
        } finally {
            Arrays.fill(buf, (byte) 0);
        }
    }

    /**
     * 解析整個檔案的位元組。回傳前（包含拋出例外時）把 {@code data} 清零。
     *
     * @param data 檔案內容
     * @return 解析結果
     * @throws ChromeCsvException 整個檔案無法匯入（空檔、缺少欄位、不是 UTF-8、太大）
     */
    public static ChromeCsv parse(byte[] data) throws ChromeCsvException {
        try {
            if (data.length > MAX_BYTES) {
                throw new ChromeCsvException(ChromeCsvException.Kind.TOO_LARGE, "檔案超過 " + MAX_BYTES + " 位元組");
            }
            return parse(data, data.length);
        } finally {
            Arrays.fill(data, (byte) 0);
        }
    }

    private static ChromeCsv parse(byte[] data, int length) throws ChromeCsvException {
        CharBuffer chars = decode(data, length);
        try {
            // 用 subSequence 而不是 position(int)：後者回傳 CharBuffer 的版本是 Java 9 才有，Android API 29 沒有
            CharSequence text = chars.length() > 0 && chars.charAt(0) == BOM
                ? chars.subSequence(1, chars.length()) : chars;
            return parse(new CsvRecords(text));
        } finally {
            if (chars.hasArray()) {
                Arrays.fill(chars.array(), '\0');
            }
        }
    }

    private static CharBuffer decode(byte[] data, int length) throws ChromeCsvException {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data, 0, length));
        } catch (CharacterCodingException e) {
            throw new ChromeCsvException(ChromeCsvException.Kind.NOT_UTF8, "不是 UTF-8 文字");
        }
    }

    private static ChromeCsv parse(CsvRecords records) throws ChromeCsvException {
        CsvRecords.Record header = records.next();
        while (header != null && header.isBlankLine()) {
            header = records.next();
        }
        if (header == null) {
            throw new ChromeCsvException(ChromeCsvException.Kind.EMPTY, "沒有標題列");
        }
        int[] index = columnIndex(header);
        int width = header.fields().size();

        List<ChromeCsvRow> rows = new ArrayList<>();
        List<SkippedLine> skipped = new ArrayList<>();
        for (CsvRecords.Record r = records.next(); r != null; r = records.next()) {
            if (rows.size() + skipped.size() >= MAX_RECORDS) {
                throw new ChromeCsvException(ChromeCsvException.Kind.TOO_LARGE, "超過 " + MAX_RECORDS + " 筆記錄");
            }
            if (r.error() != null) {
                skipped.add(new SkippedLine(r.line(), r.error()));
            } else if (r.isBlankLine()) {
                continue;
            } else if (r.fields().size() != width) {
                skipped.add(new SkippedLine(r.line(), SkippedLine.Reason.FIELD_COUNT));
            } else {
                ChromeCsvRow row = new ChromeCsvRow(r.line(), value(r, index[NAME]), value(r, index[URL]),
                    value(r, index[USERNAME]), value(r, index[PASSWORD]), value(r, index[NOTE]));
                if (row.isEmpty()) {
                    skipped.add(new SkippedLine(r.line(), SkippedLine.Reason.EMPTY_ENTRY));
                } else {
                    rows.add(row);
                }
            }
        }
        return new ChromeCsv(rows, skipped);
    }

    /** 每個已知欄位在記錄中的位置；檔案沒有的欄位為 -1。 */
    private static int[] columnIndex(CsvRecords.Record header) throws ChromeCsvException {
        if (header.error() != null) {
            throw new ChromeCsvException(ChromeCsvException.Kind.MISSING_COLUMNS, "標題列格式錯誤");
        }
        Map<String, Integer> byName = new HashMap<>();
        List<String> names = header.fields();
        for (int i = 0; i < names.size(); i++) {
            byName.putIfAbsent(names.get(i).trim().toLowerCase(Locale.ROOT), i);
        }
        int[] index = new int[COLUMNS.length];
        for (int c = 0; c < COLUMNS.length; c++) {
            index[c] = byName.getOrDefault(COLUMNS[c], -1);
        }
        if (index[URL] < 0 || index[USERNAME] < 0 || index[PASSWORD] < 0) {
            throw new ChromeCsvException(ChromeCsvException.Kind.MISSING_COLUMNS,
                "標題列缺少 url、username 或 password");
        }
        return index;
    }

    private static String value(CsvRecords.Record r, int column) {
        return column < 0 ? "" : r.fields().get(column);
    }
}
