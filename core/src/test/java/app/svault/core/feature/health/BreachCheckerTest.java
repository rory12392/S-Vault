package app.svault.core.feature.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.feature.health.BreachReport.Status;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryQuery;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BreachChecker}：外洩檢查的用戶端（AC-13-2～AC-13-4 的用戶端邏輯；確認對話框與報告頁在 T-52／T-53）。
 * 全部對 {@link FakeHibpServer}（本機 loopback），不連真正的 Have I Been Pwned。
 */
class BreachCheckerTest {

    private static final String LEAKED = "password123";
    private static final String STRONG = "g7#Kq!2vZp@9LmW$xR4t";

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC);
    private Vault vault;
    private VaultEditor editor;
    private FakeHibpServer server;

    @BeforeEach
    void open() throws IOException {
        vault = codec.create("外洩測試", TestVaults.password());
        editor = new VaultEditor(vault, clock);
        server = new FakeHibpServer();
    }

    @AfterEach
    void close() {
        server.close();
        vault.close();
    }

    private UUID add(String title, String password) {
        return editor.addEntry(vault.id(), new EntryDraft(title, "u", password, "", ""));
    }

    private BreachReport check() {
        return new BreachChecker(server.baseUrl(), 5_000).check(EntryQuery.of(vault));
    }

    private static List<UUID> ids(List<BreachHit> hits) {
        return hits.stream().map(h -> h.entry().uuid()).collect(Collectors.toList());
    }

    @Test
    @DisplayName("AC-13-2：送出的只有 SHA-1 前 5 碼（GET /range/前綴），明文與完整雜湊都不在請求裡；帶 Add-Padding 與 User-Agent")
    void ac13_2_onlyPrefixLeavesTheMachine() {
        add("A 論壇", LEAKED);
        add("B 銀行", STRONG);

        check();

        List<FakeHibpServer.Request> requests = server.requests();
        assertEquals(2, requests.size());
        List<String> expectedPaths = new ArrayList<>();
        for (String pw : List.of(LEAKED, STRONG)) {
            expectedPaths.add("/range/" + FakeHibpServer.sha1(pw).substring(0, 5));
        }
        assertEquals(expectedPaths.stream().sorted().toList(),
                requests.stream().map(FakeHibpServer.Request::path).sorted().toList());
        for (FakeHibpServer.Request r : requests) {
            assertEquals("GET", r.method());
            assertTrue(r.path().matches("/range/[0-9A-F]{5}"), r.path());
            assertEquals(null, r.query(), "不帶查詢字串（mode=ntlm 等）");
            assertEquals(0, r.body().length, "沒有請求內容");
            assertEquals(List.of("true"), r.headers().get("Add-padding"));
            assertEquals(List.of(BreachChecker.USER_AGENT), r.headers().get("User-agent"));
            String all = r.path() + r.headers();
            for (String pw : List.of(LEAKED, STRONG)) {
                assertFalse(all.contains(pw), "明文不在請求裡");
                assertFalse(all.toUpperCase().contains(FakeHibpServer.sha1(pw).substring(5)), "後綴不在請求裡");
            }
        }
    }

    @Test
    @DisplayName("AC-13-3：列出密碼出現在外洩資料中的條目與次數；共用同一密碼的條目都列出，依名稱排序")
    void ac13_3_hitsListedWithCounts() {
        UUID b = add("B 舊信箱", LEAKED);
        UUID a = add("A 論壇", LEAKED);
        add("C 銀行", STRONG);
        server.answer(LEAKED, 2_254_650L);

        BreachReport report = check();

        assertEquals(Status.COMPLETED, report.status());
        assertEquals(List.of(a, b), ids(report.hits()));
        assertEquals(List.of(2_254_650L, 2_254_650L),
                report.hits().stream().map(BreachHit::count).toList());
        assertEquals(2, report.prefixesChecked(), "兩個不重複的密碼");
        assertFalse(report.isClean());
    }

    @Test
    @DisplayName("AC-13-3：次數 0 的填充列（Add-Padding）即使後綴相同也不算命中")
    void paddingLinesAreIgnored() {
        add("A 論壇", LEAKED);
        server.answer(LEAKED, 0);

        BreachReport report = check();

        assertTrue(report.isClean());
        assertEquals(1, report.prefixesChecked());
    }

    @Test
    @DisplayName("AC-13-3：回應的後綴大小寫不拘、行尾只有 LF 也能比對")
    void responseCaseAndLineEndingsTolerated() {
        add("A 論壇", LEAKED);
        String suffix = FakeHibpServer.sha1(LEAKED).substring(5).toLowerCase();
        server.respond(prefix -> new FakeHibpServer.Reply(200, "00000000000000000000000000000000001:0\n"
                + suffix + ":7\n", 0));

        assertEquals(List.of(7L), check().hits().stream().map(BreachHit::count).toList());
    }

    @Test
    @DisplayName("規格：同一前綴只查一次（重複的密碼、前綴相同的不同密碼），結果仍各自正確")
    void samePrefixQueriedOnce() {
        String[] pair = twoPasswordsWithSamePrefix();
        UUID first = add("A", pair[0]);
        add("B", pair[1]);
        UUID dup = add("C", pair[0]);
        server.answer(pair[0], 3);

        BreachReport report = check();

        assertEquals(1, server.requests().size());
        assertEquals(1, report.prefixesChecked());
        assertEquals(List.of(first, dup), ids(report.hits()));
    }

    @Test
    @DisplayName("規格：資源回收桶的條目與沒有密碼的條目不送出")
    void recycledAndEmptyAreNotSent() {
        UUID binned = add("丟掉的", LEAKED);
        editor.recycleEntry(binned);
        add("只有備註", "");

        BreachReport report = check();

        assertEquals(List.of(), server.requests());
        assertTrue(report.isClean());
        assertEquals(0, report.prefixesChecked());
    }

    @Test
    @DisplayName("AC-13-4：連不上伺服器時回報連線失敗，不丟例外、不帶部分結果")
    void ac13_4_connectionRefused() throws IOException {
        add("A 論壇", LEAKED);
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        BreachReport report = new BreachChecker("http://127.0.0.1:" + port, 2_000).check(EntryQuery.of(vault));

        assertEquals(Status.CONNECTION_FAILED, report.status());
        assertEquals(List.of(), report.hits());
    }

    @Test
    @DisplayName("AC-13-4：伺服器回錯誤碼（429、503、404、301）時回報連線失敗；已查到的命中也不回傳")
    void ac13_4_errorStatus() {
        add("A 論壇", LEAKED);
        add("B 銀行", STRONG);
        server.answer(LEAKED, 9);
        String leakedPrefix = FakeHibpServer.sha1(LEAKED).substring(0, 5);
        for (int status : new int[] {429, 503, 404, 301}) {
            server.respond(prefix -> prefix.equals(leakedPrefix)
                    ? new FakeHibpServer.Reply(200, FakeHibpServer.sha1(LEAKED).substring(5) + ":9\r\n", 0)
                    : new FakeHibpServer.Reply(status, "", 0));

            BreachReport report = check();

            assertEquals(Status.CONNECTION_FAILED, report.status(), "狀態碼 " + status);
            assertEquals(List.of(), report.hits());
        }
    }

    @Test
    @DisplayName("AC-13-4：讀取逾時回報連線失敗，不會一直等")
    void ac13_4_timeout() {
        add("A 論壇", LEAKED);
        server.respond(prefix -> new FakeHibpServer.Reply(200, "", 3_000));

        long start = System.nanoTime();
        BreachReport report = new BreachChecker(server.baseUrl(), 300).check(EntryQuery.of(vault));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Status.CONNECTION_FAILED, report.status());
        assertTrue(millis < 2_500, "逾時後就放棄：" + millis + " ms");
    }

    @Test
    @DisplayName("AC-13-4：回應格式不對（不是 後綴:次數）時回報連線失敗，不當成沒有外洩")
    void ac13_4_malformedResponse() {
        add("A 論壇", LEAKED);
        for (String body : List.of("<html>維護中</html>", "ABC:1\r\n", "00000000000000000000000000000000001:x\r\n")) {
            server.respond(prefix -> new FakeHibpServer.Reply(200, body, 0));

            assertEquals(Status.CONNECTION_FAILED, check().status(), body);
        }
    }

    @Test
    @DisplayName("進度依前綴回報；執行緒被中斷時停止並回報取消")
    void progressAndCancel() {
        add("A", LEAKED);
        add("B", STRONG);
        add("C", "another-pass");
        List<String> calls = new ArrayList<>();
        BreachChecker checker = new BreachChecker(server.baseUrl(), 5_000);

        BreachReport done = checker.check(EntryQuery.of(vault), (d, t) -> calls.add(d + "/" + t));
        assertEquals(Status.COMPLETED, done.status());
        assertEquals(List.of("0/3", "1/3", "2/3", "3/3"), calls);

        server.answer(LEAKED, 5);
        BreachReport cancelled = checker.check(EntryQuery.of(vault), (d, t) -> {
            if (d == 1) {
                Thread.currentThread().interrupt();
            }
        });
        boolean interrupted = Thread.interrupted();
        assertEquals(Status.CANCELLED, cancelled.status());
        assertEquals(List.of(), cancelled.hits());
        assertTrue(interrupted, "保留中斷旗標給呼叫端");
    }

    @Test
    @DisplayName("規格：base 預設官方位址，可用 SVAULT_HIBP_BASE_URL 覆寫；只接受 http(s)、正的逾時")
    void baseUrlAndArguments() {
        assertEquals("https://api.pwnedpasswords.com", BreachChecker.resolveBaseUrl(null));
        assertEquals("https://api.pwnedpasswords.com", BreachChecker.resolveBaseUrl("  "));
        assertEquals("http://127.0.0.1:8080", BreachChecker.resolveBaseUrl(" http://127.0.0.1:8080/ "));
        assertEquals("SVAULT_HIBP_BASE_URL", BreachChecker.BASE_URL_ENV);
        assertEquals(10_000, BreachChecker.DEFAULT_TIMEOUT_MILLIS, "規格：連線與讀取逾時各 10 秒");
        assertEquals("http://127.0.0.1:1", new BreachChecker("http://127.0.0.1:1/", 1).baseUrl());
        assertThrows(IllegalArgumentException.class, () -> new BreachChecker("file:///c:/x", 1_000));
        assertThrows(IllegalArgumentException.class, () -> new BreachChecker("不是網址 ::", 1_000));
        assertThrows(IllegalArgumentException.class, () -> new BreachChecker("https://x", 0));
        // 資安總檢 L-2：明文 http 只給本機測試伺服器
        assertEquals("http://localhost:8080", new BreachChecker("http://localhost:8080", 1).baseUrl());
        assertEquals("http://[::1]:8080", new BreachChecker("http://[::1]:8080", 1).baseUrl());
        assertThrows(IllegalArgumentException.class, () -> new BreachChecker("http://api.pwnedpasswords.com", 1));
        assertThrows(IllegalArgumentException.class, () -> new BreachChecker("http://192.168.1.5:8080", 1));
    }

    @Test
    @DisplayName("資安總檢 L-2：回應有一列超長時回報連線失敗，不無限制地讀進記憶體")
    void overlongLineIsRejected() {
        add("A 論壇", LEAKED);
        String body = "0".repeat(100_000) + ":1\r\n";
        server.respond(prefix -> new FakeHibpServer.Reply(200, body, 0));

        assertEquals(Status.CONNECTION_FAILED, check().status());
    }

    @Test
    @DisplayName("Codex 待確認：空白列也算進列數上限，一直回空白列的伺服器回報連線失敗")
    void blankLinesCountTowardLimit() {
        add("A 論壇", LEAKED);
        String body = "\r\n".repeat(20_000);
        server.respond(prefix -> new FakeHibpServer.Reply(200, body, 0));

        assertEquals(Status.CONNECTION_FAILED, check().status());
    }

    @Test
    @DisplayName("Codex 待確認：每次都在讀取逾時前送一點點資料的伺服器，整體超過時限就放棄")
    void slowDripHitsOverallDeadline() {
        add("A 論壇", LEAKED);
        String body = "\r\n".repeat(5_000);
        server.respond(prefix -> new FakeHibpServer.Reply(200, body, 0, 20));

        long start = System.nanoTime();
        BreachReport report = new BreachChecker(server.baseUrl(), 300).check(EntryQuery.of(vault));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Status.CONNECTION_FAILED, report.status());
        assertTrue(millis < 3_000, "整體時限內就放棄：" + millis + " ms");
    }

    @Test
    @DisplayName("Codex B5：一個字一個字慢慢送、不換行的伺服器，整體時限一到就放棄（不必等到單列過長）")
    void slowCharDripWithoutNewlineHitsDeadline() {
        add("A 論壇", LEAKED);
        String body = "0".repeat(60);
        server.respond(prefix -> new FakeHibpServer.Reply(200, body, 0, 50));
        long start = System.nanoTime();
        BreachReport report = new BreachChecker(server.baseUrl(), 300).check(EntryQuery.of(vault));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertEquals(Status.CONNECTION_FAILED, report.status());
        assertTrue(millis < 2_000, "整體時限內就放棄：" + millis + " ms");
    }

    @Test
    @DisplayName("規格：日誌只記錄前綴數與命中數，不記前綴、雜湊、條目名稱或例外訊息")
    void logOnlyCounts() {
        add("秘密論壇", LEAKED);
        server.answer(LEAKED, 4);
        List<LogRecord> records = new ArrayList<>();
        Logger logger = Logger.getLogger(BreachChecker.class.getName());
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord r) {
                records.add(r);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Level old = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try {
            check();
            server.respond(prefix -> new FakeHibpServer.Reply(503, "", 0));
            check();
        } finally {
            logger.removeHandler(handler);
            logger.setLevel(old);
        }

        assertEquals(2, records.size(), "成功、失敗各一筆");
        String hash = FakeHibpServer.sha1(LEAKED);
        for (LogRecord r : records) {
            String text = r.getMessage() + " " + java.util.Arrays.toString(r.getParameters());
            assertFalse(text.contains(hash.substring(0, 5)), text);
            assertFalse(text.contains(hash.substring(5)), text);
            assertFalse(text.contains("秘密論壇"), text);
            assertEquals(null, r.getThrown(), "不附例外（訊息可能帶網址）");
        }
        assertEquals("[1, 1]", java.util.Arrays.toString(records.get(0).getParameters()));
    }

    @Test
    @DisplayName("報告與命中的 toString() 只輸出狀態與數量")
    void toStringDoesNotLeak() {
        add("秘密論壇", LEAKED);
        server.answer(LEAKED, 4);

        BreachReport report = check();

        String text = report + " " + report.hits();
        assertEquals("BreachReport[COMPLETED、前綴 1、命中 1] [BreachHit[4 次]]", text);
        assertThrows(IllegalArgumentException.class, () -> new BreachHit(report.hits().get(0).entry(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new BreachReport(Status.CONNECTION_FAILED, report.hits(), 1));
    }

    /** 找兩個 SHA-1 前 5 碼相同的不同字串（2^20 種前綴，生日問題約一千多個就會撞到）。 */
    private static String[] twoPasswordsWithSamePrefix() {
        Map<String, String> seen = new HashMap<>();
        for (int i = 0; ; i++) {
            String pw = "collide-" + i;
            String prev = seen.putIfAbsent(FakeHibpServer.sha1(pw).substring(0, 5), pw);
            if (prev != null) {
                return new String[] {prev, pw};
            }
        }
    }
}
