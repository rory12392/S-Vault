package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.vault.UrlMatch.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link UrlMatch}：網址正規化與自動填入的比對規則。
 */
class UrlMatchTest {

    @Test
    @DisplayName("AC-10-3：網址取主機名稱——小寫、去掉 www.、連接埠、帳密與結尾的點；沒寫 scheme 當成 https")
    void hostNormalization() {
        assertEquals("github.com", UrlMatch.host("https://GitHub.com/login"));
        assertEquals("github.com", UrlMatch.host("  github.com/login?next=/a b  "));
        assertEquals("github.com", UrlMatch.host("http://www.github.com:8080/"));
        assertEquals("github.com", UrlMatch.host("https://user:pw@github.com/"));
        assertEquals("github.com", UrlMatch.host("https://github.com./"));
        assertEquals("evil.example", UrlMatch.host("https://github.com@evil.example/"), "@ 前面是帳密，不是主機");
        assertEquals("www.com", UrlMatch.host("https://www.com"), "去掉 www. 後沒有點時保留原樣");
        assertEquals("xn--fsqu00a.tw", UrlMatch.host("https://例子.TW/"), "國際化網域轉成 ASCII");
        assertEquals("xn--fsqu00a.tw", UrlMatch.host("xn--fsqu00a.tw"));
        assertEquals("192.168.1.1", UrlMatch.host("192.168.1.1:8443"));
        assertEquals("[::1]", UrlMatch.host("http://[::1]:8080/"));
        assertEquals("shop.example", UrlMatch.host("shop.example/redirect?u=https://evil.example"),
                "路徑裡的 :// 不是 scheme");
    }

    @Test
    @DisplayName("AC-10-5：不是 http(s) 網址或解析不了時沒有主機名稱，不會被比對到")
    void hostRejectsOtherUrls() {
        assertNull(UrlMatch.host(null));
        assertNull(UrlMatch.host("   "));
        assertNull(UrlMatch.host("ftp://github.com/"));
        assertNull(UrlMatch.host("androidapp://com.discord"));
        assertNull(UrlMatch.host("mailto:rory@github.com"));
        assertNull(UrlMatch.host("rory@github.com"), "沒有 scheme 卻有 @ 的不是網址");
        assertNull(UrlMatch.host("not a url"));
        assertNull(UrlMatch.host("https:///path"));
        assertNull(UrlMatch.host("https://" + "a".repeat(64) + ".example/"), "標籤超過 63 字元");
    }

    @Test
    @DisplayName("AC-10-3：androidapp://<套件名稱> 取出套件名稱；其他網址沒有套件名稱")
    void androidPackage() {
        assertEquals("com.discord", UrlMatch.androidPackage("androidapp://com.discord"));
        assertEquals("com.discord", UrlMatch.androidPackage(" AndroidApp://com.discord/ "));
        assertEquals("com.example_app", UrlMatch.androidPackage("androidapp://com.example_app"));
        assertNull(UrlMatch.androidPackage("androidapp://"));
        assertNull(UrlMatch.androidPackage("androidapp://com.discord evil"));
        assertNull(UrlMatch.androidPackage("https://com.discord"));
        assertNull(UrlMatch.androidPackage(null));
    }

    @Test
    @DisplayName("AC-10-3：網頁請求——條目主機和 webDomain 相同，或是它的上層網域，才符合")
    void webDomainMatching() {
        UrlMatch github = UrlMatch.forRequest("com.android.chrome", "github.com");
        assertEquals(Level.EXACT, github.level("https://github.com/login"));
        assertEquals(Level.EXACT, github.level("www.github.com"));
        assertEquals(Level.NONE, github.level("https://login.github.com/"), "下層網域不給上層");

        UrlMatch gist = UrlMatch.forRequest("com.android.chrome", "gist.github.com");
        assertEquals(Level.PARENT_DOMAIN, gist.level("https://github.com/"));
        assertEquals(Level.EXACT, gist.level("https://gist.github.com/"));
    }

    @Test
    @DisplayName("AC-10-5：網域不符的請求不給——相似網域、後綴相同、只有單一標籤的上層、IP 的上層都不算")
    void webDomainMismatches() {
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "evilgithub.com").level("https://github.com"));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "github.com.evil.example").level("https://github.com"));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "github.com").level("https://com"), "單一標籤不當上層網域");
        assertEquals(Level.EXACT, UrlMatch.forRequest("p", "localhost").level("http://localhost:3000"));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "app.localhost").level("http://localhost"));
        assertEquals(Level.EXACT, UrlMatch.forRequest("p", "192.168.1.1").level("https://192.168.1.1/"));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "192.168.1.1").level("https://168.1.1/"), "IP 不比上層");
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "github.com").level(""));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "github.com").level(null));
    }

    @Test
    @DisplayName("C-5／L-3：http 頁面不符合明確寫 https:// 的條目；http 條目、沒寫 scheme 的條目、不知道 scheme 時照舊")
    void httpPageDoesNotGetHttpsEntries() {
        UrlMatch http = UrlMatch.forRequest("p", "http://bank.example");
        assertEquals(Level.NONE, http.level("https://bank.example/login"));
        assertEquals(Level.EXACT, http.level("http://bank.example/"));
        assertEquals(Level.EXACT, http.level("bank.example"));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "http://login.bank.example").level("https://bank.example"));
        assertEquals(Level.EXACT, UrlMatch.forRequest("p", "https://bank.example").level("https://bank.example"));
        assertEquals(Level.EXACT, UrlMatch.forRequest("p", "bank.example").level("https://bank.example"));
    }

    @Test
    @DisplayName("T-104：Keepass2Android 的附加網址欄位（KP2A_URL、KP2A_URL_1…）也算，取最好的那一個；其他自訂欄位不看")
    void kp2aAdditionalUrls() {
        EntryView e = new EntryView(UUID.randomUUID(), UUID.randomUUID(), "銀行", "u", "p", "https://old.example",
                "", Map.of("KP2A_URL", "androidapp://com.bank.app", "KP2A_URL_1", "https://login.bank.example",
                        "備用網址", "https://evil.example"), List.of(), "", Instant.EPOCH, Instant.EPOCH, 0);
        assertEquals(Level.EXACT, UrlMatch.forRequest("p", "login.bank.example").levelOf(e));
        assertEquals(Level.EXACT, UrlMatch.forRequest("com.bank.app", null).levelOf(e));
        assertEquals(Level.EXACT, UrlMatch.forRequest("p", "old.example").levelOf(e));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "evil.example").levelOf(e), "一般自訂欄位不算網址");
        assertTrue(UrlMatch.forRequest("p", "login.bank.example").matches(e));
    }

    @Test
    @DisplayName("L-3：條目主機本身是公開後綴（github.io、co.uk、*.ck）時不當上層網域，一般網域照舊")
    void publicSuffixIsNotAParentDomain() {
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "attacker.github.io").level("https://github.io"));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "evil.co.uk").level("https://co.uk"));
        assertEquals(Level.NONE, UrlMatch.forRequest("p", "a.foo.ck").level("https://foo.ck"), "萬用字元規則");
        assertEquals(Level.PARENT_DOMAIN, UrlMatch.forRequest("p", "gist.github.com").level("https://github.com"));
        assertEquals(Level.PARENT_DOMAIN, UrlMatch.forRequest("p", "login.bank.co.uk").level("https://bank.co.uk"));
        assertEquals(Level.EXACT, UrlMatch.forRequest("p", "github.io").level("https://github.io"), "完全相同仍算");
        assertTrue(PublicSuffixes.isPublicSuffix("github.io"));
        assertFalse(PublicSuffixes.isPublicSuffix("example.com"));
    }

    @Test
    @DisplayName("AC-10-3：App 請求（沒有 webDomain）——只有 androidapp://<同一個套件名稱> 的條目符合")
    void packageMatching() {
        UrlMatch discord = UrlMatch.forRequest("com.discord", null);
        assertEquals(Level.EXACT, discord.level("androidapp://com.discord"));
        assertEquals(Level.EXACT, UrlMatch.forRequest(" com.discord ", "  ").level("androidapp://com.discord"));
        assertEquals(Level.NONE, discord.level("androidapp://com.discord.evil"));
        assertEquals(Level.NONE, discord.level("androidapp://com"));
        assertEquals(Level.NONE, discord.level("https://discord.com/"), "App 請求不用網址猜");
        assertEquals(Level.NONE, UrlMatch.forRequest(null, null).level("androidapp://com.discord"));
        assertEquals(Level.NONE, UrlMatch.forRequest("  ", "").level("androidapp://com.discord"));
    }

    @Test
    @DisplayName("AC-10-5：有 webDomain 的請求只比網域，不因為瀏覽器的套件名稱給出條目；webDomain 解析不了時一律不符")
    void webRequestIgnoresPackage() {
        UrlMatch web = UrlMatch.forRequest("com.android.chrome", "discord.com");
        assertEquals(Level.NONE, web.level("androidapp://com.android.chrome"));
        assertEquals(Level.EXACT, web.level("https://discord.com/login"));

        UrlMatch broken = UrlMatch.forRequest("com.android.chrome", "not a domain");
        assertEquals(Level.NONE, broken.level("androidapp://com.android.chrome"));
        assertEquals(Level.NONE, broken.level("https://not a domain/"));
    }

    @Test
    @DisplayName("AC-10-5：matches(條目) 供手動挑選時判斷要不要先警告")
    void matchesEntry() {
        UrlMatch req = UrlMatch.forRequest("com.android.chrome", "accounts.github.com");
        assertTrue(req.matches(entry("https://github.com")));
        assertFalse(req.matches(entry("https://gitlab.com")));
    }

    private static EntryView entry(String url) {
        return new EntryView(UUID.randomUUID(), UUID.randomUUID(), "t", "u", "p", url, "", Map.of(), List.of(), "",
                Instant.EPOCH, Instant.EPOCH, 0);
    }
}
