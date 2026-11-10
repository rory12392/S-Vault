package app.svault.core.vault;

import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;

/**
 * 自動填入的比對規則（AC-10-3、AC-10-5）。
 *
 * <p>一次自動填入請求建立一個（{@link #forRequest}），再逐一比對條目的網址欄位：
 * <ul>
 *   <li>請求有 {@code webDomain}（瀏覽器裡的網頁）：條目網址的主機名稱和它相同，或是它的上層網域，才符合。
 *       這時<b>不看</b>套件名稱——瀏覽器的套件名稱對所有網站都一樣，用它比對會把帳密交給任何網頁。</li>
 *   <li>請求沒有 {@code webDomain}（一般 App）：只有網址是 {@code androidapp://<同一個套件名稱>} 的條目符合；
 *       不從網址猜 App（例如 discord.com 不等於 com.discord）。</li>
 * </ul>
 *
 * <p>網址只用 {@link URI}、{@link IDN} 解析，不連網（core 的限制，CONVENTIONS §2）。沒有內建公開後綴清單，
 * 所以「上層網域」只排除單一標籤（{@code com}、{@code localhost}）與 IP；條目網址是 {@code co.uk} 這類
 * 公開後綴時會比對到其下所有網站，這是使用者自己存的網址，接受這個寬度。
 */
public final class UrlMatch {

    /** 比對結果；{@link EntryQuery#autofill} 先列 {@link #EXACT} 再列 {@link #PARENT_DOMAIN}。 */
    public enum Level {
        /** 不符：不得提供帳密（AC-10-5）。 */
        NONE,
        /** 條目主機是請求網域的上層網域（條目 github.com、請求 gist.github.com）。 */
        PARENT_DOMAIN,
        /** 主機名稱相同，或套件名稱相同。 */
        EXACT
    }

    private static final String ANDROID_APP = "androidapp://";

    /** 正規化後的請求網域；請求沒有網域時為 null。 */
    private final String requestHost;
    /** 請求帶了 webDomain 但解析不了：一律不符，也不退回套件比對。 */
    private final boolean webRequest;
    private final String requestPackage;
    /** 請求網域明確帶的 scheme（{@code http}／{@code https}）；不知道時為 null。 */
    private final String requestScheme;

    private UrlMatch(String requestHost, boolean webRequest, String requestPackage, String requestScheme) {
        this.requestHost = requestHost;
        this.webRequest = webRequest;
        this.requestPackage = requestPackage;
        this.requestScheme = requestScheme;
    }

    /**
     * @param packageName 請求來源 App 的套件名稱（{@code AssistStructure.getActivityComponent()}）；可為 null
     * @param webDomain   網頁網域（{@code ViewNode.getWebDomain()}）；一般 App 為 null 或空字串。可以帶 scheme
     *                    （{@code http://bank.example}，{@code ViewNode.getWebScheme()}）：http 頁面不符合明確寫
     *                    {@code https://} 的條目（C-5、L-3，D-188）
     * @return 這次請求的比對器
     */
    public static UrlMatch forRequest(String packageName, String webDomain) {
        boolean web = webDomain != null && !webDomain.trim().isEmpty();
        String pkg = packageName == null || packageName.trim().isEmpty() ? null : packageName.trim();
        return new UrlMatch(web ? host(webDomain) : null, web, pkg, web ? scheme(webDomain) : null);
    }

    /**
     * @param entryUrl 條目的網址欄位；可為 null
     * @return 比對結果
     */
    public Level level(String entryUrl) {
        if (webRequest) {
            String entryHost = host(entryUrl);
            if (requestHost == null || entryHost == null) {
                return Level.NONE;
            }
            // 存成 https 的帳密不給 http 頁面：沒有加密的頁面可能被中間人換成假的登入表單（C-5、L-3）
            if ("http".equals(requestScheme) && "https".equals(scheme(entryUrl))) {
                return Level.NONE;
            }
            return domainLevel(entryHost, requestHost);
        }
        String entryPackage = androidPackage(entryUrl);
        return requestPackage != null && requestPackage.equals(entryPackage) ? Level.EXACT : Level.NONE;
    }

    /**
     * 使用者從「搜尋保管庫」手動挑條目時，用它判斷要不要先顯示警告（AC-10-5）。
     *
     * @param entry 條目
     * @return 條目符合這次請求
     */
    public boolean matches(EntryView entry) {
        return levelOf(entry) != Level.NONE;
    }

    /**
     * 條目的網址欄位，加上 Keepass2Android 慣用的附加網址自訂欄位（{@code KP2A_URL}、{@code KP2A_URL_1}…；T-104），
     * 取符合程度最好的。其他自訂欄位不當成網址。
     *
     * @param entry 條目
     * @return 比對結果
     */
    public Level levelOf(EntryView entry) {
        Level best = level(entry.url());
        for (Map.Entry<String, String> f : entry.customFields().entrySet()) {
            if (best == Level.EXACT) {
                break;
            }
            if (isKp2aUrl(f.getKey())) {
                Level l = level(f.getValue());
                if (l.ordinal() > best.ordinal()) {
                    best = l;
                }
            }
        }
        return best;
    }

    private static boolean isKp2aUrl(String key) {
        if (key.equals("KP2A_URL")) {
            return true;
        }
        if (!key.startsWith("KP2A_URL_") || key.length() == "KP2A_URL_".length()) {
            return false;
        }
        for (int i = "KP2A_URL_".length(); i < key.length(); i++) {
            if (key.charAt(i) < '0' || key.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * 網址的主機名稱，正規化成比對用的形式：小寫 ASCII（國際化網域轉成 {@code xn--}）、去掉結尾的點與開頭的
     * {@code www.}（去掉後仍有點時）；連接埠、帳密、路徑都不算。沒有 scheme 時當成 https。
     *
     * @param url 網址，或只有網域
     * @return 主機名稱；不是 http(s)、或解析不了時為 null
     */
    public static String host(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        int sep = s.indexOf("://");
        boolean hasScheme = sep > 0 && isScheme(s.substring(0, sep));
        if (hasScheme) {
            String scheme = s.substring(0, sep).toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            s = s.substring(sep + 3);
        }
        // 只解析 authority：路徑與查詢字串裡的空白等字元不影響主機名稱
        int end = indexOfAny(s, "/?#");
        String authority = end < 0 ? s : s.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0 && !hasScheme) {
            // 沒有 scheme 卻有 @（例如 mailto:、帳號）不是網址，免得把 @ 後面當成主機
            return null;
        }
        String hostPort = authority.substring(at + 1);
        String host;
        String port;
        int colon = hostPort.lastIndexOf(':');
        if (hostPort.startsWith("[") || colon < 0) {
            host = hostPort;
            port = "";
        } else {
            host = hostPort.substring(0, colon);
            port = hostPort.substring(colon);
        }
        try {
            String ascii = host.startsWith("[") ? host : IDN.toASCII(host);
            String parsed = new URI("https://" + ascii + port + "/").getHost();
            return parsed == null ? null : normalize(parsed);
        } catch (IllegalArgumentException | URISyntaxException e) {
            return null;
        }
    }

    /**
     * @param url 網址
     * @return {@code androidapp://<套件名稱>} 的套件名稱；其他網址、或套件名稱含不合法字元時為 null
     */
    public static String androidPackage(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        if (!s.regionMatches(true, 0, ANDROID_APP, 0, ANDROID_APP.length())) {
            return null;
        }
        String rest = s.substring(ANDROID_APP.length());
        int end = indexOfAny(rest, "/?#");
        String pkg = end < 0 ? rest : rest.substring(0, end);
        if (pkg.isEmpty()) {
            return null;
        }
        for (int i = 0; i < pkg.length(); i++) {
            char c = pkg.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '.')) {
                return null;
            }
        }
        return pkg;
    }

    private static Level domainLevel(String entryHost, String requestHost) {
        if (entryHost.equals(requestHost)) {
            return Level.EXACT;
        }
        // 單一標籤（com、localhost）不當上層網域；IP 沒有上層網域（168.1.1 不是 192.168.1.1 的上層）；
        // 公開後綴（github.io、co.uk）底下誰都能註冊，也不當上層網域（L-3）
        if (entryHost.indexOf('.') < 0 || isIpLiteral(requestHost) || PublicSuffixes.isPublicSuffix(entryHost)) {
            return Level.NONE;
        }
        return requestHost.endsWith("." + entryHost) ? Level.PARENT_DOMAIN : Level.NONE;
    }

    /** @return 明確寫出的 scheme（小寫）；沒寫時 {@code null} */
    private static String scheme(String url) {
        if (url == null) {
            return null;
        }
        String s = url.trim();
        int sep = s.indexOf("://");
        return sep > 0 && isScheme(s.substring(0, sep)) ? s.substring(0, sep).toLowerCase(Locale.ROOT) : null;
    }

    private static String normalize(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (h.isEmpty()) {
            return null;
        }
        if (h.startsWith("www.") && h.indexOf('.', 4) >= 0) {
            h = h.substring(4);
        }
        return h;
    }

    /** IPv6（URI 保留方括號）或最後一段全是數字（頂層網域不會全是數字）。 */
    private static boolean isIpLiteral(String host) {
        if (host.startsWith("[")) {
            return true;
        }
        String last = host.substring(host.lastIndexOf('.') + 1);
        for (int i = 0; i < last.length(); i++) {
            if (last.charAt(i) < '0' || last.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** RFC 3986 的 scheme：字母開頭，之後是字母、數字、+、-、.。 */
    private static boolean isScheme(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean letter = c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
            if (!letter && (i == 0 || !(c >= '0' && c <= '9' || c == '+' || c == '-' || c == '.'))) {
                return false;
            }
        }
        return true;
    }

    private static int indexOfAny(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }
}
