package app.svault.android.feature.autofill;

import java.util.Set;

/**
 * 已知的瀏覽器套件名稱（瀏覽器的 {@code webDomain} 為空時視為不符）。
 *
 * <p>瀏覽器的套件名稱對所有網站都一樣，只能靠 {@code webDomain} 判斷是哪個網站（D-153）。拿不到網域時如果照一般 App
 * 用套件名稱比對，存了 {@code androidapp://com.android.chrome} 的條目會被交給任何網頁，所以由本清單先擋下。
 * 清單以外的瀏覽器拿不到網域時照一般 App 處理：只有條目網址是 {@code androidapp://<它的套件名稱>} 才會符合，
 * 使用者不會為瀏覽器本身存這種條目，風險只在這種少見的情況。新增時依 Play 商店的套件名稱。
 *
 * <p>反過來，{@code webDomain} 只採信本清單的瀏覽器（{@link #trustedDomain}；資安總檢 H-1）：網域是請求的 App 自己
 * 填的，App 內的 WebView 載入真正的網站、或原生欄位呼叫 {@code ViewStructure.setWebDomain}，都能自稱任何網域，
 * 再從 WebView 讀走填進去的帳密。清單以外的 App 一律照一般 App 用套件名稱比對。
 */
final class Browsers {

    private static final Set<String> PACKAGES = Set.of(
            "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
            "org.chromium.chrome", "com.google.android.apps.chrome",
            "org.mozilla.firefox", "org.mozilla.firefox_beta", "org.mozilla.fenix", "org.mozilla.focus",
            "org.mozilla.klar",
            "com.microsoft.emmx", "com.microsoft.emmx.beta",
            "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta",
            "com.brave.browser", "com.brave.browser_beta",
            "com.opera.browser", "com.opera.browser.beta", "com.opera.mini.native", "com.opera.gx",
            "com.vivaldi.browser", "com.duckduckgo.mobile.android", "com.kiwibrowser.browser",
            "com.ecosia.android", "com.yandex.browser", "com.UCMobile.intl", "com.mi.globalbrowser",
            "com.heytap.browser", "com.huawei.browser", "org.torproject.torbrowser",
            "com.google.android.googlequicksearchbox");

    private Browsers() {
    }

    /**
     * @param packageName 請求來源的套件名稱
     * @return 是已知的瀏覽器
     */
    static boolean isBrowser(String packageName) {
        return packageName != null && PACKAGES.contains(packageName);
    }

    /**
     * 只採信「套件名稱＋簽署憑證」都對得上 {@link KnownBrowserSigners} 的 App 自稱的網域（C-1）：套件名稱誰都能用，
     * 側載一個冒用沒安裝的瀏覽器名稱的 App，憑證對不上就不採信。上面的 {@link #isBrowser} 只用來「擋」（瀏覽器沒有網域時
     * 不比對），名稱就夠，不需要憑證。
     *
     * @param packageName 請求來源的套件名稱
     * @param webDomain   請求帶的網頁網域；可為 {@code null}
     * @param signers     查已安裝 App 的簽署憑證
     * @return 可以拿來比對的網域：來源是驗證過的瀏覽器且網域不是空白時去掉前後空白的網域，其他一律 {@code null}
     */
    static String trustedDomain(String packageName, String webDomain, Signers signers) {
        if (webDomain == null || webDomain.isBlank() || packageName == null) {
            return null;
        }
        Set<String> known = KnownBrowserSigners.SIGNERS.get(packageName);
        if (known == null) {
            return null;
        }
        for (String actual : signers.of(packageName)) {
            if (known.contains(actual)) {
                return webDomain.trim();
            }
        }
        return null;
    }
}
