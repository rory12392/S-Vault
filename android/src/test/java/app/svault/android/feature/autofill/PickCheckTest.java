package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.svault.core.vault.EntryView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.Test;

/**
 * AC-10-5（T-83）：從「搜尋保管庫」手動挑條目時，哪些要先警告；條目選擇畫面列哪些條目。
 */
public class PickCheckTest {

    private static final String CHROME = "com.android.chrome";
    private static final Signers GENUINE = FillPlannerTest.GENUINE;

    @Test
    public void ac10_5_appRequestMatchesOnlySamePackage() {
        PickCheck check = new PickCheck("com.discord", null, GENUINE);
        assertTrue(check.matches(entry("androidapp://com.discord", "u", "p")));
        // 網址像同一個服務也算不符：不從網址猜 App（UrlMatch 的規則）
        assertFalse(check.matches(entry("https://discord.com/", "u", "p")));
        assertFalse(check.matches(entry("androidapp://com.discord.fake", "u", "p")));
        assertFalse(check.matches(entry("", "u", "p")));
        assertEquals("com.discord", check.requestSource());
    }

    @Test
    public void ac10_5_webRequestMatchesDomainOrParent() {
        PickCheck check = new PickCheck(CHROME, "gist.github.com", GENUINE);
        assertTrue(check.matches(entry("https://github.com/login", "u", "p")));
        assertTrue(check.matches(entry("https://gist.github.com", "u", "p")));
        assertFalse(check.matches(entry("https://evilgithub.com", "u", "p")));
        // 網頁請求不看套件名稱
        assertFalse(check.matches(entry("androidapp://" + CHROME, "u", "p")));
        assertEquals("gist.github.com", check.requestSource());
    }

    @Test
    public void ac10_5_nonBrowserDomainIsNotTrusted() {
        // 資安總檢 H-1：非瀏覽器自稱的網域不算數，挑網站條目要先警告，警告裡寫的是 App 的套件名稱
        PickCheck check = new PickCheck("com.evil.app", "bank.example", GENUINE);
        assertFalse(check.matches(entry("https://bank.example/", "u", "p")));
        assertEquals("com.evil.app", check.requestSource());
        assertTrue(new PickCheck("com.bank.app", "bank.example", GENUINE)
                .matches(entry("androidapp://com.bank.app", "u", "p")));
    }

    @Test
    public void c1_spoofedBrowserDomainIsNotTrusted() {
        // C-1：冒用 Chrome 名稱、憑證不對的 App 自稱的網域不算數，挑網站條目要先警告
        PickCheck check = new PickCheck(CHROME, "bank.example", pkg -> java.util.Set.of("00".repeat(32)));
        assertFalse(check.matches(entry("https://bank.example/", "u", "p")));
        assertEquals(CHROME, check.requestSource());
    }

    @Test
    public void ac10_5_browserWithoutDomainNeverMatches() {
        // 規格：瀏覽器的 webDomain 為空時視為不符——連 androidapp://<瀏覽器> 的條目也要先警告
        for (String domain : new String[] {null, "", "  "}) {
            PickCheck check = new PickCheck(CHROME, domain, GENUINE);
            assertFalse(check.matches(entry("androidapp://" + CHROME, "u", "p")));
            assertFalse(check.matches(entry("https://github.com", "u", "p")));
            assertEquals(CHROME, check.requestSource());
        }
    }

    @Test
    public void ac10_5_entrySourceForWarning() {
        assertEquals("github.com", PickCheck.entrySource(entry("https://www.GitHub.com/login", "u", "p")));
        assertEquals("com.discord", PickCheck.entrySource(entry("androidapp://com.discord", "u", "p")));
        assertEquals("com.discord", PickCheck.entrySource(entry("AndroidApp://com.discord/", "u", "p")));
        assertNull(PickCheck.entrySource(entry("", "u", "p")));
        assertNull(PickCheck.entrySource(entry(null, "u", "p")));
        assertNull(PickCheck.entrySource(entry("mailto:me@example.com", "u", "p")));
    }

    @Test
    public void ac10_3_pickListSkipsEntriesWithNothingToFill() {
        EntryView both = entry("https://a.example", "u", "p");
        EntryView userOnly = entry("https://b.example", "u", "");
        EntryView passOnly = entry("https://c.example", null, "p");
        EntryView empty = entry("https://d.example", "", null);
        assertEquals(List.of(both, userOnly, passOnly), PickCheck.fillable(List.of(both, userOnly, passOnly, empty)));
    }

    private static EntryView entry(String url, String username, String password) {
        return new EntryView(UUID.randomUUID(), UUID.randomUUID(), "條目", username, password, url, "", Map.of(),
                List.of(), "", Instant.EPOCH, Instant.EPOCH, 0);
    }
}
