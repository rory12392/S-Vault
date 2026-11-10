package app.svault.core.feature.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link HostNames}：重複判定用的主機名稱（AC-14-4：忽略 {@code www.} 與大小寫）。
 */
class HostNamesTest {

    @Test
    @DisplayName("AC-14-4：忽略 www. 與大小寫，路徑、查詢字串、連接埠、帳號資訊不算")
    void comparableHost() {
        assertEquals("example.com", HostNames.comparable("https://www.Example.COM/login?next=/a#top"));
        assertEquals("example.com", HostNames.comparable("http://example.com:8080"));
        assertEquals("example.com", HostNames.comparable("https://user:pw@WWW.example.com./"));
        assertEquals("example.com", HostNames.comparable("  example.com/path  "));
        assertEquals("example.com", HostNames.comparable("www.example.com"));
        assertEquals("shop.example.com", HostNames.comparable("https://shop.example.com"));
        assertEquals("www2.example.com", HostNames.comparable("https://www2.example.com"));
        assertEquals("com.example.app", HostNames.comparable("android://fakehash@com.example.app/"));
        assertEquals("[::1]", HostNames.comparable("http://[::1]:8443/"));
        assertEquals("localhost", HostNames.comparable("localhost:3000"));
    }

    @Test
    @DisplayName("沒有主機名稱的網址回傳空字串（不參與重複判定）")
    void noHost() {
        assertEquals("", HostNames.comparable(""));
        assertEquals("", HostNames.comparable("   "));
        assertEquals("", HostNames.comparable(null));
        assertEquals("", HostNames.comparable("not a url"));
        assertEquals("", HostNames.comparable("mailto:alice@example.com"));
        assertEquals("", HostNames.comparable("https:///path"));
        assertEquals("", HostNames.comparable("alice@example.com"));
    }

    @Test
    @DisplayName("顯示用的主機名稱只轉小寫、保留 www.")
    void displayHost() {
        assertEquals("www.example.com", HostNames.display("https://WWW.Example.com/login"));
        assertEquals("", HostNames.display("not a url"));
    }
}
