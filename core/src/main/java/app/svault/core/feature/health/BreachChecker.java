package app.svault.core.feature.health;

import app.svault.core.vault.EntryQuery;
import app.svault.core.vault.EntryView;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 外洩檢查的用戶端（AC-13-2～AC-13-4）：Have I Been Pwned 的
 * Pwned Passwords 範圍查詢（k-anonymity）。
 *
 * <ul>
 *   <li>對每個不重複的密碼算 SHA-1（大寫十六進位），只把前 5 碼送到 {@code GET {base}/range/{前綴}}；
 *       回應的 {@code 後綴:次數} 在本機比對。同一前綴只查一次。</li>
 *   <li>請求帶 {@code Add-Padding: true}（回應補到 800–1,000 列，旁人無法從回應大小推測前綴）；
 *       填充列的次數一律是 0，直接略過。另帶 {@code User-Agent}（官方要求，缺少時回 403）。</li>
 *   <li>只看 {@link EntryQuery#all()}：資源回收桶與沒有密碼的條目不送出（和 {@link WeakReuseAnalyzer} 一致）。</li>
 *   <li>任何連線錯誤、逾時、非 200 的狀態碼或格式不對的回應 → {@link BreachReport.Status#CONNECTION_FAILED}，
 *       不丟例外、不回傳部分結果；弱／重複報告另外算，照常顯示（AC-13-4）。</li>
 * </ul>
 *
 * <p>只在使用者按下「檢查外洩」並確認後由畫面呼叫（AC-13-2；確認對話框在 T-52／T-53）。同步執行、
 * 每個前綴一個請求，平台外殼放到背景執行緒，用 {@link Progress} 顯示進度；中斷執行緒即可取消。
 * 用 {@link HttpURLConnection}：Android 沒有 {@code java.net.http}。
 */
public final class BreachChecker {

    /** 官方的 Pwned Passwords API（2026-10-22 查證：免金鑰、沒有頻率限制）。 */
    public static final String DEFAULT_BASE_URL = "https://api.pwnedpasswords.com";
    /** 覆寫 base 的環境變數（測試指向本機假伺服器；正式版不設定，Architecture §7）。 */
    public static final String BASE_URL_ENV = "SVAULT_HIBP_BASE_URL";
    /** 連線與讀取的逾時各 10 秒（規格 §1）。 */
    public static final int DEFAULT_TIMEOUT_MILLIS = 10_000;
    /** 官方要求每個請求都帶 User-Agent；不帶版本號，免得多送出可識別的資訊。 */
    public static final String USER_AGENT = "S-Vault-Password-Health";

    private static final Logger LOG = Logger.getLogger(BreachChecker.class.getName());
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final int PREFIX_LENGTH = 5;
    private static final int SUFFIX_LENGTH = 35;
    /** 正常回應約 800–1,000 列；遠超過時視為不是 Pwned Passwords 的回應，避免讀進無限的資料。 */
    private static final int MAX_LINES = 10_000;

    /** 一個前綴的整個回應最多花幾倍的逾時（連線與讀取逾時之外的整體時限）。 */
    private static final int OVERALL_FACTOR = 3;
    /** 單列字元上限：正常的一列是 35 碼後綴、冒號、次數與 {@code \r}，不到 50 字元。 */
    private static final int MAX_LINE_CHARS = 64;

    /** 進度回呼（在呼叫 {@link #check} 的執行緒上呼叫）。 */
    @FunctionalInterface
    public interface Progress {
        /**
         * @param done  已查完的前綴數（開始時先以 0 呼叫一次）
         * @param total 要查的前綴總數
         */
        void onProgress(int done, int total);
    }

    private final String baseUrl;
    private final int timeoutMillis;

    /**
     * 正式用：base 取 {@link #BASE_URL_ENV}（沒設定時用官方位址），逾時 {@link #DEFAULT_TIMEOUT_MILLIS}。
     */
    public BreachChecker() {
        this(resolveBaseUrl(System.getenv(BASE_URL_ENV)), DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * @param baseUrl       API 位址（http 或 https，結尾的斜線會去掉）
     * @param timeoutMillis 連線與讀取各自的逾時（毫秒）
     * @throws IllegalArgumentException 位址不是 http(s) 網址，或逾時不是正數
     */
    public BreachChecker(String baseUrl, int timeoutMillis) {
        String trimmed = stripTrailingSlash(baseUrl.trim());
        try {
            URI uri = new URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null) {
                throw new IllegalArgumentException("外洩檢查的位址只接受 http(s)");
            }
            // 明文 http 只給本機的測試伺服器：環境變數被誤設或被同一使用者的程式改掉時，前綴不會以明文送到別處（資安總檢 L-2）
            if (scheme.equals("http") && !isLoopback(uri.getHost())) {
                throw new IllegalArgumentException("外洩檢查的位址除了本機以外只接受 https");
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("外洩檢查的位址格式不對", e);
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("逾時必須是正數");
        }
        this.baseUrl = trimmed;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * @param override {@link #BASE_URL_ENV} 的值（可為 null）
     * @return 覆寫值（去掉前後空白與結尾斜線）；null 或空白時是 {@link #DEFAULT_BASE_URL}
     */
    public static String resolveBaseUrl(String override) {
        // 不用 String.isBlank()：Android API 29 沒有（Animal Sniffer 會擋）
        if (override == null || override.trim().isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        return stripTrailingSlash(override.trim());
    }

    /**
     * 讀一列（去掉 {@code \r\n}／{@code \n}），超過 {@link #MAX_LINE_CHARS} 字元就停：{@code readLine()} 不限長度，
     * 惡意伺服器回一列超長資料就能耗盡記憶體（資安總檢 L-2）。
     *
     * @param deadline 整體時限（{@code System.nanoTime()}）：每讀一個字元就檢查，一個字一個字慢慢送、不換行的伺服器
     *                 也拖不過它（Codex M-04 審查 B5；單次讀取仍可能等到讀取逾時，所以最壞是時限再加一次逾時）
     * @return 一列；串流結束時 {@code null}
     * @throws IOException 讀取失敗、單列過長或超過整體時限
     */
    private static String boundedLine(BufferedReader reader, long deadline) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int ch;
        while ((ch = reader.read()) != -1) {
            if (System.nanoTime() > deadline) {
                throw new IOException("回應超過整體時限");
            }
            if (ch == '\n') {
                return sb.toString();
            }
            if (sb.length() >= MAX_LINE_CHARS) {
                throw new IOException("回應單列過長");
            }
            sb.append((char) ch);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static boolean isLoopback(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    private static String stripTrailingSlash(String url) {
        String s = url;
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /**
     * @return 實際使用的 API 位址
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * 不回報進度的 {@link #check(EntryQuery, Progress)}。
     *
     * @param query 條目查詢（檢查當下的快照）
     * @return 檢查結果
     */
    public BreachReport check(EntryQuery query) {
        return check(query, (done, total) -> { });
    }

    /**
     * @param query    條目查詢（檢查當下的快照）
     * @param progress 進度回呼
     * @return 檢查結果；連線失敗或被中斷時不丟例外，以狀態回報。中斷時保留執行緒的中斷旗標
     * @throws NullPointerException query 或 progress 為 null
     */
    public BreachReport check(EntryQuery query, Progress progress) {
        return check(query.all(), progress);
    }

    /**
     * 給平台外殼用（T-52）：模組從 {@code ModuleHost.unlockedEntries()} 拿到的是 {@code all()} 的清單。
     * 呼叫端只傳不含資源回收桶的清單（{@code all()} 本來就不含）。
     *
     * @param all      要檢查的條目（檢查當下的快照，依名稱排序）
     * @param progress 進度回呼
     * @return 檢查結果；規則同 {@link #check(EntryQuery, Progress)}
     * @throws NullPointerException all 或 progress 為 null
     */
    public BreachReport check(List<EntryView> all, Progress progress) {
        List<EntryView> entries = new ArrayList<>();
        List<Pending> pendingOfEntry = new ArrayList<>();
        Map<String, List<Pending>> byPrefix = new LinkedHashMap<>();
        // 以密碼字串當鍵找出不重複的密碼（字串本來就在 EntryView 裡，不多產生副本）；建好前綴分組就丟掉。
        Map<String, Pending> byPassword = new HashMap<>();
        for (EntryView e : all) {
            String password = e.password();
            if (password.isEmpty()) {
                continue;
            }
            Pending p = byPassword.get(password);
            if (p == null) {
                char[] hex = sha1Hex(password);
                p = new Pending(Arrays.copyOfRange(hex, PREFIX_LENGTH, hex.length));
                byPrefix.computeIfAbsent(new String(hex, 0, PREFIX_LENGTH), k -> new ArrayList<>()).add(p);
                Arrays.fill(hex, '\0');
                byPassword.put(password, p);
            }
            entries.add(e);
            pendingOfEntry.add(p);
        }
        byPassword.clear();

        int total = byPrefix.size();
        int done = 0;
        try {
            progress.onProgress(0, total);
            for (Map.Entry<String, List<Pending>> group : byPrefix.entrySet()) {
                if (Thread.currentThread().isInterrupted()) {
                    LOG.log(Level.INFO, "外洩檢查已取消：檢查了 {0} 個前綴", done);
                    return new BreachReport(BreachReport.Status.CANCELLED, List.of(), done);
                }
                queryRange(group.getKey(), group.getValue());
                done++;
                progress.onProgress(done, total);
            }
        } catch (IOException e) {
            if (Thread.currentThread().isInterrupted()) {
                LOG.log(Level.INFO, "外洩檢查已取消：檢查了 {0} 個前綴", done);
                return new BreachReport(BreachReport.Status.CANCELLED, List.of(), done);
            }
            // 例外訊息可能帶網址（含前綴），只記例外類型與計數
            LOG.log(Level.WARNING, "外洩檢查連線失敗（{0}）：已檢查 {1} 個前綴",
                    new Object[] {e.getClass().getSimpleName(), done});
            return new BreachReport(BreachReport.Status.CONNECTION_FAILED, List.of(), done);
        } finally {
            for (List<Pending> group : byPrefix.values()) {
                for (Pending p : group) {
                    Arrays.fill(p.suffix, '\0');
                }
            }
        }

        List<BreachHit> hits = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            long count = pendingOfEntry.get(i).count;
            if (count > 0) {
                hits.add(new BreachHit(entries.get(i), count));
            }
        }
        LOG.log(Level.INFO, "外洩檢查完成：檢查了 {0} 個前綴、{1} 筆命中", new Object[] {done, hits.size()});
        return new BreachReport(BreachReport.Status.COMPLETED, hits, done);
    }

    /** 查一個前綴，把命中的次數寫進這個前綴底下各個密碼的 {@link Pending#count}。 */
    private void queryRange(String prefix, List<Pending> group) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(baseUrl + "/range/" + prefix).toURL().openConnection();
        try {
            c.setConnectTimeout(timeoutMillis);
            c.setReadTimeout(timeoutMillis);
            c.setRequestMethod("GET");
            c.setUseCaches(false);
            // 官方直接回 200；被導向別處代表位址設錯或被攔截，不跟過去
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("Add-Padding", "true");
            int status = c.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + status);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.US_ASCII))) {
                int lines = 0;
                // 讀取逾時只管「兩次收到資料之間」：每次都在逾時前送一點點的伺服器能一直拖住，所以另設整體時限
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos((long) timeoutMillis * OVERALL_FACTOR);
                String line;
                while ((line = boundedLine(reader, deadline)) != null) {
                    if (System.nanoTime() > deadline) {
                        throw new IOException("回應超過整體時限");
                    }
                    // 空白列也算：只回空白列的伺服器不能繞過列數上限
                    if (++lines > MAX_LINES) {
                        throw new IOException("回應列數過多");
                    }
                    line = line.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    long count = parseCount(line);
                    if (count == 0) {
                        continue; // Add-Padding 的填充列
                    }
                    for (Pending p : group) {
                        if (p.matches(line)) {
                            p.count = count;
                        }
                    }
                }
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * @return {@code 後綴:次數} 的次數
     * @throws IOException 不是 35 碼十六進位後綴加上非負整數
     */
    private static long parseCount(String line) throws IOException {
        if (line.length() <= SUFFIX_LENGTH + 1 || line.charAt(SUFFIX_LENGTH) != ':') {
            throw new IOException("回應格式不對");
        }
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            if (Character.digit(line.charAt(i), 16) < 0) {
                throw new IOException("回應格式不對");
            }
        }
        try {
            long count = Long.parseLong(line.substring(SUFFIX_LENGTH + 1));
            if (count < 0) {
                throw new IOException("回應格式不對");
            }
            return count;
        } catch (NumberFormatException e) {
            throw new IOException("回應格式不對", e);
        }
    }

    /**
     * SHA-1 的大寫十六進位。自己轉換：Android API 29 沒有 {@code HexFormat}。
     * 中間的位元組陣列用完清零；回傳的字元陣列由呼叫端清除。
     */
    private static char[] sha1Hex(String password) {
        byte[] utf8 = password.getBytes(StandardCharsets.UTF_8);
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-1").digest(utf8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 必備的 SHA-1 不存在", e);
        } finally {
            Arrays.fill(utf8, (byte) 0);
        }
        char[] hex = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            hex[i * 2] = HEX[(digest[i] >> 4) & 0x0F];
            hex[i * 2 + 1] = HEX[digest[i] & 0x0F];
        }
        Arrays.fill(digest, (byte) 0);
        return hex;
    }

    /** 一個不重複的密碼：雜湊後綴（檢查結束就清除）與查到的次數。 */
    private static final class Pending {
        final char[] suffix;
        long count;

        Pending(char[] suffix) {
            this.suffix = suffix;
        }

        /** 回應的後綴不分大小寫（官方範例是大寫，這裡不依賴它）。 */
        boolean matches(String line) {
            for (int i = 0; i < SUFFIX_LENGTH; i++) {
                if (Character.toUpperCase(line.charAt(i)) != suffix[i]) {
                    return false;
                }
            }
            return true;
        }
    }
}
