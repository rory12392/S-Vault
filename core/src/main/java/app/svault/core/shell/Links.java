package app.svault.core.shell;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * 條目網址的「開啟」（用系統瀏覽器）。只交給瀏覽器 http／https 的網址：網址欄是使用者或匯入的
 * 資料，{@code file:}、{@code javascript:}、{@code intent:}、{@code content:} 或其他會啟動程式的 scheme 不開。
 * 這裡只解析字串，不連網。兩平台共用（T-14 從 desktop／android 的雙胞胎檔案搬來）。
 */
public final class Links {

    private Links() {
    }

    /**
     * @param url 條目的網址欄
     * @return 可以交給瀏覽器的網址；沒有 scheme 的（{@code github.com/login}）補上 {@code https://}；
     *         空白或不是 http／https 時為空
     */
    public static Optional<String> browsable(String url) {
        String s = Texts.strip(url == null ? "" : url);
        if (s.isEmpty() || s.chars().anyMatch(Character::isWhitespace)) {
            return Optional.empty();
        }
        if (!s.contains("://")) {
            // 只有「網域開頭」才補 https；像 mailto:、javascript: 這種有 scheme 但沒有 // 的不補
            if (s.indexOf(':') >= 0 && s.indexOf(':') < firstSlash(s) && !looksLikeHostPort(s)) {
                return Optional.empty();
            }
            s = "https://" + s;
        }
        try {
            URI uri = new URI(s);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || uri.getHost() == null) {
                return Optional.empty();
            }
            return Optional.of(uri.toString());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static int firstSlash(String s) {
        int i = s.indexOf('/');
        return i < 0 ? s.length() : i;
    }

    /** {@code example.com:8443/x}：冒號後面是連接埠號碼。 */
    private static boolean looksLikeHostPort(String s) {
        int colon = s.indexOf(':');
        int end = firstSlash(s);
        if (colon + 1 >= end) {
            return false;
        }
        for (int i = colon + 1; i < end; i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
