package app.svault.core.vault;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 公開後綴（{@code co.uk}、{@code github.io} 這類任何人都能在底下註冊網域的後綴；L-3）。資料是 Mozilla 的
 * Public Suffix List（{@code public_suffix_list.dat}，MPL 2.0，原檔不修改，見 NOTICES；2026-11-09 版），第一次用到時
 * 讀進記憶體。只回答「這個主機名稱本身是不是公開後綴」，給 {@link UrlMatch} 判斷能不能當上層網域。
 */
final class PublicSuffixes {

    private static final String RESOURCE = "public_suffix_list.dat";

    private PublicSuffixes() {
    }

    /**
     * @param host 正規化後的主機名稱（小寫 ASCII，{@link UrlMatch#host}）
     * @return 是公開後綴（含萬用字元規則 {@code *.ck}，扣掉例外規則 {@code !www.ck}）
     */
    static boolean isPublicSuffix(String host) {
        Rules rules = Rules.INSTANCE;
        if (rules.exceptions.contains(host)) {
            return false;
        }
        if (rules.exact.contains(host)) {
            return true;
        }
        int dot = host.indexOf('.');
        return dot > 0 && rules.wildcardParents.contains(host.substring(dot + 1));
    }

    /** 延遲載入（類別第一次被用到才讀檔）。 */
    private static final class Rules {

        static final Rules INSTANCE = load();

        final Set<String> exact = new HashSet<>();
        final Set<String> wildcardParents = new HashSet<>();
        final Set<String> exceptions = new HashSet<>();

        private static Rules load() {
            Rules r = new Rules();
            try (InputStream in = PublicSuffixes.class.getResourceAsStream(RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("缺少 " + RESOURCE);
                }
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    String rule = line.trim();
                    if (rule.isEmpty() || rule.startsWith("//")) {
                        continue;
                    }
                    if (rule.startsWith("!")) {
                        r.exceptions.add(ascii(rule.substring(1)));
                    } else if (rule.startsWith("*.")) {
                        r.wildcardParents.add(ascii(rule.substring(2)));
                    } else {
                        r.exact.add(ascii(rule));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("無法讀取 " + RESOURCE, e);
            }
            return r;
        }

        // 清單裡的國際化網域是 Unicode，UrlMatch 比的是 xn-- 形式
        private static String ascii(String label) {
            try {
                return IDN.toASCII(label).toLowerCase(Locale.ROOT);
            } catch (IllegalArgumentException e) {
                return label.toLowerCase(Locale.ROOT);
            }
        }
    }
}
