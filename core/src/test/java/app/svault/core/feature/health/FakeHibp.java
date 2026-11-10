package app.svault.core.feature.health;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 本機的假 Pwned Passwords 伺服器（CONVENTIONS §5：外洩檢查的測試不連外網）。只聽 loopback，記錄請求路徑，
 * 讓 {@link HealthReportModel} 的測試確認「送出去的只有前 5 碼」（AC-13-2）。原本是 android 單元測試的（android.jar 沒有
 * {@code com.sun.net.httpserver}），T-117 把模型搬進 core 時原樣帶過來；{@link BreachChecker} 本身的測試用 {@link FakeHibpServer}。
 */
final class FakeHibp implements AutoCloseable {

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<String> paths = Collections.synchronizedList(new ArrayList<>());
    /** 前綴 → 後綴 → 次數。 */
    private final Map<String, Map<String, Long>> answers = new HashMap<>();
    private volatile int status = 200;
    private volatile long delayMillis;

    FakeHibp() throws IOException {
        server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::acceptLoop, "fake-hibp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getLocalPort();
    }

    /** @return 收到的請求路徑（{@code /range/XXXXX}） */
    List<String> paths() {
        synchronized (paths) {
            return List.copyOf(paths);
        }
    }

    /** 讓 {@code password} 在回應中出現 {@code count} 次。 */
    synchronized void answer(String password, long count) {
        String hash = sha1(password);
        answers.computeIfAbsent(hash.substring(0, 5), k -> new HashMap<>()).put(hash.substring(5), count);
    }

    /** 之後的請求都回這個狀態碼（例：503 模擬服務掛掉）。 */
    void failWith(int code) {
        status = code;
    }

    /** 每個請求先等這麼久（測試取消）。 */
    void delay(long millis) {
        delayMillis = millis;
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "fake-hibp-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return; // 關閉
            }
        }
    }

    private void handle(Socket socket) {
        try (Socket s = socket) {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(),
                    StandardCharsets.US_ASCII));
            String requestLine = in.readLine();
            if (requestLine == null) {
                return;
            }
            for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                // 略過標頭
            }
            String path = requestLine.split(" ")[1];
            paths.add(path);
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
            StringBuilder sb = new StringBuilder();
            String prefix = path.startsWith("/range/") ? path.substring(7).toUpperCase(Locale.ROOT) : "";
            synchronized (this) {
                for (Map.Entry<String, Long> e : answers.getOrDefault(prefix, Map.of()).entrySet()) {
                    sb.append(e.getKey()).append(':').append(e.getValue()).append("\r\n");
                }
            }
            sb.append(String.format(Locale.ROOT, "%035X:0\r\n", 1)); // Add-Padding 的填充列
            byte[] body = sb.toString().getBytes(StandardCharsets.US_ASCII);
            String head = "HTTP/1.1 " + status + " X\r\nContent-Type: text/plain\r\nContent-Length: " + body.length
                    + "\r\nConnection: close\r\n\r\n";
            OutputStream out = s.getOutputStream();
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(body);
            out.flush();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // 用戶端已經斷線（取消）：測試只看用戶端的結果
        }
    }

    static String sha1(String password) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format(Locale.ROOT, "%02X", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
