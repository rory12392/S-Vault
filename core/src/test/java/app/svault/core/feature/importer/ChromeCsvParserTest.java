package app.svault.core.feature.importer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.EntryDraft;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChromeCsvParser}：RFC 4180、標題列辨識欄位、格式錯誤的列與行號（AC-14-1、AC-14-2）。
 * 欄位格式依規格與 RFC 4180 自製假資料，另有 Chrome 實際匯出的假資料樣本（SETUP S-TD2，{@code chrome-sample.csv}）。
 */
class ChromeCsvParserTest {

    private static final String HEADER = "name,url,username,password,note\n";

    @Test
    @DisplayName("S-TD2：Chrome 實際匯出的樣本（3 筆假資料）：標題列、名稱是網域、網址只留到網域、備註含逗號與換行")
    void parsesRealChromeExport() throws Exception {
        ChromeCsv csv;
        try (InputStream in = getClass().getResourceAsStream("chrome-sample.csv")) {
            assertNotNull(in, "測試資源 chrome-sample.csv 不存在");
            csv = ChromeCsvParser.read(in);
        }

        assertEquals(List.of(), csv.skipped());
        List<ChromeCsvRow> rows = csv.rows();
        assertEquals(3, rows.size());
        assertRow(rows.get(0), 2, "example.com", "https://example.com/", "alice", "Fake-Pass-1", "");
        assertRow(rows.get(1), 3, "forum.example.net", "https://forum.example.net/", "carol", "Fake-Pass-3", "");
        assertRow(rows.get(2), 4, "shop.example.org", "https://shop.example.org/", "bob", "Fake-Pass-2",
            "第一行, 有逗號\n第二行");
    }

    @Test
    @DisplayName("AC-14-2：假資料樣本的每一欄都和原檔一致（引號、逗號、跳脫的引號、欄位內換行）")
    void parsesFakeSample() throws Exception {
        ChromeCsv csv;
        try (InputStream in = getClass().getResourceAsStream("chrome-fake.csv")) {
            assertNotNull(in, "測試資源 chrome-fake.csv 不存在");
            csv = ChromeCsvParser.read(in);
        }

        assertEquals(List.of(), csv.skipped());
        List<ChromeCsvRow> rows = csv.rows();
        assertEquals(4, rows.size());
        assertRow(rows.get(0), 2, "example.com", "https://www.example.com/login", "alice@example.com",
            "Fake-Pass-001", "");
        assertRow(rows.get(1), 3, "shop.example.org", "https://shop.example.org/", "bob", "pa,ss\"word", "");
        assertRow(rows.get(2), 4, "forum.example.net", "https://forum.example.net/", "carol", "Fake-Pass-003",
            "第一行，有逗號, comma\n第二行");
        assertRow(rows.get(3), 6, "", "android://fakehash@com.example.app/", "dave", "Fake-Pass-004", "");
    }

    @Test
    @DisplayName("AC-14-2：100 筆的 CSV 解析出 100 筆，五個欄位都和原檔一致")
    void parsesHundredRows() throws Exception {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 1; i <= 100; i++) {
            sb.append(csvField("站台 " + i)).append(',')
                .append(csvField("https://site" + i + ".example.com/")).append(',')
                .append(csvField("user" + i + "@example.com")).append(',')
                .append(csvField(password(i))).append(',')
                .append(csvField(i % 10 == 0 ? "備註 " + i + "\n第二行, 有逗號" : "")).append('\n');
        }

        ChromeCsv csv = parse(sb.toString());

        assertEquals(List.of(), csv.skipped());
        assertEquals(100, csv.rows().size());
        for (int i = 1; i <= 100; i++) {
            ChromeCsvRow row = csv.rows().get(i - 1);
            assertEquals("站台 " + i, row.name());
            assertEquals("https://site" + i + ".example.com/", row.url());
            assertEquals("user" + i + "@example.com", row.username());
            assertEquals(password(i), row.password());
            assertEquals(i % 10 == 0 ? "備註 " + i + "\n第二行, 有逗號" : "", row.note());
        }
    }

    @Test
    @DisplayName("AC-14-2：格式錯誤的列跳過並列出行號（欄位數不符、引號後還有字、全部空白），其他列照常解析")
    void skipsMalformedRowsWithLineNumbers() throws Exception {
        String text = HEADER
            + "ok1,https://a.example.com,u1,p1,\n"           // 第 2 行
            + "too,few,fields\n"                              // 第 3 行：欄位太少
            + "ok2,https://b.example.com,u2,p2,\"多行\n備註\"\n" // 第 4–5 行
            + "a,b,c,d,e,f\n"                                 // 第 6 行：欄位太多
            + "bad,\"https://c\"x,u3,p3,\n"                   // 第 7 行：引號後還有字
            + ",,,,\n"                                        // 第 8 行：全部空白
            + "ok3,https://d.example.com,u4,p4,\n";          // 第 9 行

        ChromeCsv csv = parse(text);

        assertEquals(List.of(2, 4, 9), csv.rows().stream().map(ChromeCsvRow::line).toList());
        assertEquals(List.of(
            new SkippedLine(3, SkippedLine.Reason.FIELD_COUNT),
            new SkippedLine(6, SkippedLine.Reason.FIELD_COUNT),
            new SkippedLine(7, SkippedLine.Reason.TEXT_AFTER_QUOTE),
            new SkippedLine(8, SkippedLine.Reason.EMPTY_ENTRY)), csv.skipped());
        assertEquals("多行\n備註", csv.rows().get(1).note());
    }

    @Test
    @DisplayName("AC-14-2：引號沒有關閉時，從那一列開始到檔尾都跳過，行號是那一列的開頭")
    void unclosedQuoteSkipsRest() throws Exception {
        ChromeCsv csv = parse(HEADER + "ok,https://a.example.com,u,p,\nbad,https://b,u,\"p\nnext,https://c,u,p,\n");

        assertEquals(1, csv.rows().size());
        assertEquals(List.of(new SkippedLine(3, SkippedLine.Reason.UNCLOSED_QUOTE)), csv.skipped());
    }

    @Test
    @DisplayName("標題列決定欄位：順序不寫死、名稱不分大小寫、多的欄位忽略、name 與 note 可以沒有")
    void headerDecidesColumns() throws Exception {
        ChromeCsv csv = parse("Password, URL ,extra,Username\nsecret,https://a.example.com,x,alice\n");

        ChromeCsvRow row = csv.rows().get(0);
        assertRow(row, 2, "", "https://a.example.com", "alice", "secret", "");
    }

    @Test
    @DisplayName("UTF-8 BOM 去除；CRLF 與 CR 換行都能用，欄位內的換行原樣保留")
    void bomAndLineEndings() throws Exception {
        byte[] body = (HEADER.replace("\n", "\r\n")
            + "a,https://a.example.com,u,p,\"一\r\n二\"\r\n"
            + "b,https://b.example.com,u,p,\r"
            + "c,https://c.example.com,u,p,").getBytes(StandardCharsets.UTF_8);
        byte[] data = new byte[body.length + 3];
        data[0] = (byte) 0xEF;
        data[1] = (byte) 0xBB;
        data[2] = (byte) 0xBF;
        System.arraycopy(body, 0, data, 3, body.length);

        ChromeCsv csv = ChromeCsvParser.read(new ByteArrayInputStream(data));

        assertEquals(List.of(), csv.skipped());
        assertEquals(List.of(2, 4, 5), csv.rows().stream().map(ChromeCsvRow::line).toList());
        assertEquals("一\r\n二", csv.rows().get(0).note());
        assertEquals("c", csv.rows().get(2).name());
    }

    @Test
    @DisplayName("空白行不算格式錯誤，也不算進筆數")
    void blankLinesIgnored() throws Exception {
        ChromeCsv csv = parse(HEADER + "\na,https://a.example.com,u,p,\n\n\n");

        assertEquals(1, csv.rows().size());
        assertEquals(3, csv.rows().get(0).line());
        assertEquals(List.of(), csv.skipped());
    }

    @Test
    @DisplayName("標題列缺少 url、username 或 password 時整個檔案不能匯入")
    void missingColumns() {
        ChromeCsvException e = assertThrows(ChromeCsvException.class, () -> parse("name,url,username\na,b,c\n"));
        assertEquals(ChromeCsvException.Kind.MISSING_COLUMNS, e.kind());
        assertEquals(ChromeCsvException.Kind.MISSING_COLUMNS,
            assertThrows(ChromeCsvException.class, () -> parse("\"name,url,username,password\n")).kind());
    }

    @Test
    @DisplayName("空檔案、不是 UTF-8、超過大小上限時整個檔案不能匯入")
    void unreadableFiles() {
        assertEquals(ChromeCsvException.Kind.EMPTY, assertThrows(ChromeCsvException.class, () -> parse("")).kind());
        assertEquals(ChromeCsvException.Kind.EMPTY,
            assertThrows(ChromeCsvException.class, () -> parse("\n\n")).kind());

        byte[] latin1 = (HEADER + "café,https://a,u,p,\n").getBytes(StandardCharsets.ISO_8859_1);
        assertEquals(ChromeCsvException.Kind.NOT_UTF8,
            assertThrows(ChromeCsvException.class, () -> ChromeCsvParser.read(new ByteArrayInputStream(latin1)))
                .kind());

        InputStream huge = new InputStream() {
            private long left = ChromeCsvParser.MAX_BYTES + 1;

            @Override
            public int read() {
                return left-- > 0 ? 'a' : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (left <= 0) {
                    return -1;
                }
                int n = (int) Math.min(len, left);
                java.util.Arrays.fill(b, off, off + n, (byte) 'a');
                left -= n;
                return n;
            }
        };
        assertEquals(ChromeCsvException.Kind.TOO_LARGE,
            assertThrows(ChromeCsvException.class, () -> ChromeCsvParser.read(huge)).kind());
    }

    @Test
    @DisplayName("C-7：一列的欄位數超過上限時邊讀邊停、整列跳過，不先配置全部欄位；下一列照常解析")
    void tooManyFieldsStopsEarly() throws ChromeCsvException {
        String commas = ",".repeat(2_000_000);
        ChromeCsv csv = parse(HEADER + commas + "\n" + "n,https://a,u,p,\n");
        assertEquals(1, csv.rows().size());
        assertEquals(List.of(new SkippedLine(2, SkippedLine.Reason.FIELD_COUNT)), csv.skipped());

        assertEquals(ChromeCsvException.Kind.MISSING_COLUMNS,
            assertThrows(ChromeCsvException.class, () -> parse(commas + "\n")).kind(), "標題列欄位太多");
    }

    @Test
    @DisplayName("C-7：記錄數（含跳過的列）超過上限時整個檔案不能匯入")
    void tooManyRecords() {
        StringBuilder sb = new StringBuilder(HEADER);
        for (int i = 0; i <= ChromeCsvParser.MAX_RECORDS; i++) {
            sb.append(i % 2 == 0 ? "n,u,a,p,\n" : "x\n");
        }
        assertEquals(ChromeCsvException.Kind.TOO_LARGE,
            assertThrows(ChromeCsvException.class, () -> parse(sb.toString())).kind());
    }

    @Test
    @DisplayName("讀檔的 I/O 錯誤照常拋出，不吞掉")
    void ioErrorPropagates() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("測試：讀取失敗");
            }
        };
        assertThrows(IOException.class, () -> ChromeCsvParser.read(broken));
    }

    @Test
    @DisplayName("名稱空白時依序改用網址的主機名稱、網址、帳號，都沒有時用「（未命名）」")
    void draftTitleFallback() {
        assertEquals("GitHub", row("GitHub", "https://github.com", "u").toDraft().title());
        assertEquals("www.example.com", row("  ", "https://WWW.Example.com/login", "u").toDraft().title());
        assertEquals("not a url", row("", "not a url", "u").toDraft().title());
        assertEquals("alice", row("", "", "alice").toDraft().title());
        assertEquals("（未命名）", row("", "", "").toDraft().title());

        EntryDraft d = new ChromeCsvRow(2, "n", "https://a", "u", "p", "note").toDraft();
        assertEquals("n", d.title());
        assertEquals("u", d.username());
        assertEquals("p", d.password());
        assertEquals("https://a", d.url());
        assertEquals("note", d.notes());
    }

    @Test
    @DisplayName("列與解析結果的 toString 不含帳號、密碼等內容（CONVENTIONS §2）")
    void toStringHidesContent() throws Exception {
        ChromeCsvRow row = new ChromeCsvRow(7, "Site", "https://a", "alice", "Fake-Secret", "note");
        assertFalse(row.toString().contains("Fake-Secret"));
        assertFalse(row.toString().contains("alice"));
        assertTrue(row.toString().contains("7"));

        ChromeCsv csv = parse(HEADER + "Site,https://a,alice,Fake-Secret,\n");
        assertFalse(csv.toString().contains("Fake-Secret"));
    }

    @Test
    @DisplayName("讀進來的位元組讀完就清零（明文不留在暫存陣列）")
    void clearsBuffer() throws Exception {
        byte[] data = (HEADER + "a,https://a,u,Fake-Secret,\n").getBytes(StandardCharsets.UTF_8);
        byte[] copy = data.clone();
        ChromeCsvParser.parse(copy);
        assertArrayEquals(new byte[data.length], copy);
    }

    private static ChromeCsv parse(String text) throws ChromeCsvException {
        return ChromeCsvParser.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    private static ChromeCsvRow row(String name, String url, String username) {
        return new ChromeCsvRow(2, name, url, username, "p", "");
    }

    private static String password(int i) {
        return "Fake,\"Pass\"-" + i;
    }

    /** 依 RFC 4180 寫一個欄位：含逗號、引號或換行時加引號，引號寫兩次。 */
    private static String csvField(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static void assertRow(ChromeCsvRow row, int line, String name, String url, String username,
                                  String password, String note) {
        assertEquals(line, row.line(), "行號");
        assertEquals(name, row.name(), "name");
        assertEquals(url, row.url(), "url");
        assertEquals(username, row.username(), "username");
        assertEquals(password, row.password(), "password");
        assertEquals(note, row.note(), "note");
    }
}
