package app.svault.core.feature.health;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * 本機的假 Pwned Passwords 伺服器（CONVENTIONS §5：外洩檢查的測試不連外網）。只聽 loopback，
 * 記錄每個請求的方法、路徑、查詢字串、標頭與內容，讓測試檢查「送出去的只有前 5 碼」（AC-13-2）。
 *
 * <p>預設回應：每個前綴都回幾列填充（次數 0）與一列不相干的真實列；{@link #answer} 指定某個密碼的次數，
 * {@link #respond} 可以整個換掉回應（錯誤碼、延遲、格式錯誤）。
 */
final class FakeHibpServer implements AutoCloseable {

    /** 一個收到的請求。 */
    record Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
    }

    /**
     * 自訂回應。
     *
     * @param dripMillis 大於 0 時用 chunked 傳輸、每送一列就停這麼久（模擬一點一點送資料的慢伺服器）
     */
    record Reply(int status, String body, long delayMillis, long dripMillis) {

        Reply(int status, String body, long delayMillis) {
            this(status, body, delayMillis, 0);
        }
    }

    private final HttpServer server;
    private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    /** 前綴 → 後綴 → 次數（測試指定的命中）。 */
    private final Map<String, Map<String, Long>> answers = new HashMap<>();
    private volatile Function<String, Reply> responder = this::defaultReply;

    FakeHibpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** @return 給 {@link BreachChecker} 的 base（不含結尾的斜線） */
    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    List<Request> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    /** 讓 {@code password} 的雜湊在回應中出現 {@code count} 次。 */
    void answer(String password, long count) {
        String hash = sha1(password);
        answers.computeIfAbsent(hash.substring(0, 5), k -> new HashMap<>()).put(hash.substring(5), count);
    }

    /** 以前綴決定整個回應（取代預設的回應）。 */
    void respond(Function<String, Reply> responder) {
        this.responder = responder;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }
        String path = exchange.getRequestURI().getRawPath();
        requests.add(new Request(exchange.getRequestMethod(), path, exchange.getRequestURI().getRawQuery(),
                Map.copyOf(exchange.getRequestHeaders()), body));
        String prefix = path.startsWith("/range/") ? path.substring("/range/".length()) : "";
        Reply reply = responder.apply(prefix.toUpperCase(Locale.ROOT));
        try {
            if (reply.delayMillis() > 0) {
                Thread.sleep(reply.delayMillis());
            }
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.dripMillis() > 0) {
                exchange.sendResponseHeaders(reply.status(), 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    // 有換行時逐列送；整段沒有換行時逐字送（Codex B5：每次都在讀取逾時前送一個字、不換行）
                    String[] pieces = reply.body().contains("\n") ? reply.body().split("(?<=\n)")
                            : reply.body().split("(?<=.)");
                    for (String line : pieces) {
                        os.write(line.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                        Thread.sleep(reply.dripMillis());
                    }
                }
                return;
            }
            exchange.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(out);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 用戶端已經因為逾時斷線：測試只關心用戶端的結果
        } finally {
            exchange.close();
        }
    }

    /** 真實 API 的樣子：每列 {@code 後綴:次數}、CRLF 分隔；加 {@code Add-Padding} 時多出次數 0 的填充列。 */
    private Reply defaultReply(String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("0018A45C4D1DEF81644B54AB7F969B88D65:1\r\n");
        for (int i = 0; i < 3; i++) {
            sb.append(String.format(Locale.ROOT, "%035X:0\r\n", i + 1));
        }
        for (Map.Entry<String, Long> e : answers.getOrDefault(prefix, Map.of()).entrySet()) {
            sb.append(e.getKey()).append(':').append(e.getValue()).append("\r\n");
        }
        return new Reply(200, sb.toString(), 0);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** 測試端自己算雜湊（大寫十六進位），和被測程式的轉換互相獨立。 */
    static String sha1(String password) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02X", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
