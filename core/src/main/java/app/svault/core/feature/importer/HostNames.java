package app.svault.core.feature.importer;

import java.util.Locale;

/**
 * 從網址取出主機名稱，給重複判定（AC-14-4）與條目名稱的備案用。
 *
 * <p>不用 {@link java.net.URI}：Chrome 匯出的網址不一定合法（沒有協定、含空白），{@code URI} 會整個拒絕。
 * T-25 在核心加網址正規化後評估改用核心的版本（規格的整合待辦）。
 */
final class HostNames {

    private HostNames() {
    }

    /**
     * 比對用的主機名稱：小寫、去掉開頭的 {@code www.} 與結尾的點。
     *
     * @param url 網址；可以沒有協定（{@code example.com/login}）
     * @return 主機名稱；取不出來時為空字串（不參與重複判定）
     */
    static String comparable(String url) {
        String host = display(url);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /**
     * 顯示用的主機名稱：小寫、去掉結尾的點，保留 {@code www.}。
     *
     * @param url 網址
     * @return 主機名稱；取不出來時為空字串
     */
    static String display(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        int scheme = s.indexOf("://");
        boolean hasScheme = scheme >= 0;
        if (hasScheme) {
            s = s.substring(scheme + 3);
        }
        String authority = s.substring(0, firstOf(s, "/?#\\"));
        if (hasScheme) {
            // 帳號資訊（android://<雜湊>@<套件名稱>、user:pw@host）不是主機名稱
            authority = authority.substring(authority.lastIndexOf('@') + 1);
        } else if (authority.indexOf('@') >= 0) {
            // 沒有協定又有 @：多半是填錯欄位的電子郵件，不當成網址
            return "";
        }
        String host = stripPort(authority, hasScheme);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || !validChars(host)) {
            return "";
        }
        return host.toLowerCase(Locale.ROOT);
    }

    /** 去掉連接埠；沒有協定時冒號後面不是數字（{@code mailto:…}、{@code javascript:…}）就不是網址。 */
    private static String stripPort(String authority, boolean hasScheme) {
        if (authority.startsWith("[")) {
            int end = authority.indexOf(']');
            return end < 0 ? "" : authority.substring(0, end + 1);
        }
        int colon = authority.indexOf(':');
        if (colon < 0) {
            return authority;
        }
        if (!hasScheme && !digits(authority.substring(colon + 1))) {
            return "";
        }
        return authority.substring(0, colon);
    }

    private static int firstOf(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return s.length();
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    /** 主機名稱不會有空白與控制字元；有的話整個欄位多半不是網址（例如填了說明文字）。 */
    private static boolean validChars(String host) {
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= ' ' || c == 0x7F) {
                return false;
            }
        }
        return true;
    }
}
