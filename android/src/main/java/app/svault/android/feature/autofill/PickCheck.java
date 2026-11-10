package app.svault.android.feature.autofill;

import app.svault.core.vault.EntryView;
import app.svault.core.vault.UrlMatch;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「搜尋保管庫」手動挑條目時的規則（AC-10-5，T-83）：挑的條目和這次請求不符時，條目選擇畫面先警告、使用者確認後才交回帳密。
 * 比對規則同建議清單（core 的 {@link UrlMatch}），另外照 {@link FillPlanner}：已知瀏覽器沒有網域時一律不符；
 * 不是瀏覽器時不看它自稱的網域（{@link Browsers#trustedDomain}，H-1）。
 * 不保存條目，{@code toString()} 用預設的（只有物件代號）。
 */
final class PickCheck {

    private static final String ANDROID_APP = "androidapp://";

    private final String packageName;
    private final String webDomain;
    private final boolean browserWithoutDomain;
    private final boolean appSignerChanged;
    private final UrlMatch match;

    /**
     * @param packageName 請求來源的套件名稱
     * @param webDomain   網頁網域；一般 App 為 {@code null}（有值也只在驗證過的瀏覽器時採信）
     * @param signers     查瀏覽器的簽署憑證（C-1）
     */
    PickCheck(String packageName, String webDomain, Signers signers) {
        this(packageName, webDomain, null, signers, AppSignerPins.OFF);
    }

    /**
     * @param packageName 請求來源的套件名稱
     * @param webDomain   網頁網域
     * @param scheme      網頁的 scheme；不知道時 {@code null}（http 頁面挑 https 條目要先警告，C-5）
     * @param signers     查簽署憑證
     * @param pins        一般 App 的憑證紀錄：換了憑證的 App 挑 {@code androidapp://} 條目也要先警告（I-5）
     */
    PickCheck(String packageName, String webDomain, String scheme, Signers signers, AppSignerPins pins) {
        this.packageName = packageName;
        this.webDomain = Browsers.trustedDomain(packageName, webDomain, signers);
        this.appSignerChanged = this.webDomain == null && !pins.trusted(packageName, signers.of(packageName));
        this.browserWithoutDomain = this.webDomain == null && Browsers.isBrowser(packageName);
        this.match = UrlMatch.forRequest(packageName, scheme == null || this.webDomain == null ? this.webDomain
                : scheme + "://" + this.webDomain);
    }

    /**
     * @param entry 使用者挑的條目
     * @return 符合這次請求（不用警告）
     */
    boolean matches(EntryView entry) {
        // 瀏覽器拿不到網域時不知道是哪個網站：androidapp://<瀏覽器> 的條目也不能直接交出去
        return !browserWithoutDomain && !appSignerChanged && match.matches(entry);
    }

    /** @return 警告裡「要求填入的」那一方：網域，沒有時是套件名稱 */
    String requestSource() {
        return webDomain != null ? webDomain : packageName;
    }

    /**
     * @param entry 條目
     * @return 警告裡條目那一方：網址的主機名稱、{@code androidapp://} 的套件名稱；沒有網址或認不得時為 {@code null}
     */
    static String entrySource(EntryView entry) {
        String url = entry.url();
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        if (trimmed.toLowerCase(Locale.ROOT).startsWith(ANDROID_APP)) {
            String pkg = trimmed.substring(ANDROID_APP.length());
            int slash = pkg.indexOf('/');
            pkg = slash < 0 ? pkg : pkg.substring(0, slash);
            return pkg.isEmpty() ? null : pkg;
        }
        return UrlMatch.host(trimmed);
    }

    /**
     * @param entries 搜尋結果
     * @return 有帳號或密碼可填的條目（順序不變）
     */
    static List<EntryView> fillable(List<EntryView> entries) {
        List<EntryView> out = new ArrayList<>();
        for (EntryView e : entries) {
            if (hasText(e.username()) || hasText(e.password())) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }
}
