package app.svault.android.feature.autofill;

import java.util.List;

/**
 * 一次請求的畫面裡找到的登入欄位（{@link FieldScanner}）。只有欄位代號與網域，沒有欄位的內容。
 *
 * @param usernames 帳號欄（依畫面順序）
 * @param passwords 密碼欄（依畫面順序）
 * @param webDomain 網頁網域（第一個有值的節點）；一般 App 為 {@code null}
 * @param webScheme 同一個節點的 scheme（{@code http}／{@code https}）；不知道時為 {@code null}（C-5、L-3）
 * @param <I>       欄位代號的型別
 */
record FieldScan<I>(List<I> usernames, List<I> passwords, String webDomain, String webScheme) {

    FieldScan {
        usernames = List.copyOf(usernames);
        passwords = List.copyOf(passwords);
    }

    FieldScan(List<I> usernames, List<I> passwords, String webDomain) {
        this(usernames, passwords, webDomain, null);
    }

    /**
     * @param trustedDomain 採信的網域（{@code Browsers#trustedDomain}）；{@code null} 表示不比網域
     * @return 給 core 比對用的網域：知道 scheme 時帶上（{@code http://bank.example}），http 頁面才不會拿到 https 條目
     */
    String requestDomain(String trustedDomain) {
        if (trustedDomain == null || webScheme == null || webScheme.isBlank()) {
            return trustedDomain;
        }
        return webScheme.trim() + "://" + trustedDomain;
    }

    /** @return 至少有一個帳號或密碼欄 */
    boolean hasFields() {
        return !usernames.isEmpty() || !passwords.isEmpty();
    }
}
