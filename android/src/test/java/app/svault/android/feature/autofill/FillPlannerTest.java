package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.svault.android.feature.autofill.FillPlan.Outcome;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.Test;

/**
 * AC-10-3、AC-10-5（比對）：一次請求要提供哪些條目。比對規則本身在 core 的 {@code UrlMatch}（T-25），
 * 這裡測模組負責的部分：瀏覽器沒有網域、上鎖、停用、自己的 App、沒有欄位。
 */
public class FillPlannerTest {

    private static final String OWN = "app.svault.android";
    private static final String CHROME = "com.android.chrome";

    private final UUID root = UUID.randomUUID();
    private final List<EntryView> entries = new ArrayList<>();
    private int queries;
    /** 裝的都是原廠版：簽署憑證就是清單上的那一把。 */
    static final Signers GENUINE = pkg -> KnownBrowserSigners.SIGNERS.getOrDefault(pkg, java.util.Set.of());
    private final FillPlanner planner = new FillPlanner(OWN, GENUINE);

    private final FieldScan<String> login = new FieldScan<>(List.of("u"), List.of("p"), null);

    @Test
    public void ac10_3_appRequestGetsPackageEntries() {
        EntryView discord = add("Discord", "androidapp://com.discord");
        add("Discord 網頁", "https://discord.com/");
        FillPlan<String> plan = planner.plan(login, "com.discord", true, unlocked());
        assertEquals(Outcome.ENTRIES, plan.outcome());
        assertEquals(List.of(discord), plan.entries());
        assertEquals(login, plan.fields());
    }

    @Test
    public void ac10_3_browserRequestMatchesDomainOnly() {
        add("Chrome 本身", "androidapp://" + CHROME);
        EntryView github = add("GitHub", "https://github.com/login");
        FillPlan<String> plan = planner.plan(withDomain("github.com"), CHROME, true, unlocked());
        assertEquals(Outcome.ENTRIES, plan.outcome());
        assertEquals(List.of(github), plan.entries());
    }

    @Test
    public void ac10_5_browserWithoutDomainOnlyOffersSearch() {
        // 不呼叫比對：否則會把 androidapp://com.android.chrome 的條目交給任何網頁（T-25 留給本任務）
        add("Chrome 本身", "androidapp://" + CHROME);
        for (String browser : List.of(CHROME, "org.mozilla.firefox", "com.microsoft.emmx", "com.sec.android.app.sbrowser")) {
            FillPlan<String> plan = planner.plan(login, browser, true, unlocked());
            assertEquals(browser, Outcome.SEARCH_ONLY, plan.outcome());
            assertTrue(plan.entries().isEmpty());
        }
        FillPlan<String> blank = planner.plan(withDomain("  "), CHROME, true, unlocked());
        assertEquals(Outcome.SEARCH_ONLY, blank.outcome());
        assertEquals(0, queries);
    }

    @Test
    public void ac10_5_mismatchGivesNoCredentials() {
        add("GitHub", "https://github.com/");
        add("Discord", "androidapp://com.discord");
        FillPlan<String> web = planner.plan(withDomain("evilgithub.com"), CHROME, true, unlocked());
        assertEquals(Outcome.SEARCH_ONLY, web.outcome());
        assertTrue(web.entries().isEmpty());
        FillPlan<String> app = planner.plan(login, "com.discord.fake", true, unlocked());
        assertEquals(Outcome.SEARCH_ONLY, app.outcome());
        assertTrue(app.entries().isEmpty());
    }

    /** {@link AppSignerPinsTest} 用：一筆 androidapp://{@code pkg} 的條目，用指定的憑證與固定紀錄規劃一次。 */
    FillPlan<String> planWith(Signers signers, AppSignerPins pins, String pkg) {
        if (entries.isEmpty()) {
            add("App", "androidapp://" + pkg);
        }
        return new FillPlanner(OWN, signers, pins).plan(login, pkg, true, unlocked());
    }

    @Test
    public void c5_httpPageDoesNotGetHttpsEntries() {
        // C-5、L-3：存成 https 的帳密不出現在 http 頁面的建議列（D-188）
        add("銀行", "https://bank.example/");
        FieldScan<String> http = new FieldScan<>(List.of("u"), List.of("p"), "bank.example", "http");
        assertEquals(Outcome.SEARCH_ONLY, planner.plan(http, CHROME, true, unlocked()).outcome());
        FieldScan<String> https = new FieldScan<>(List.of("u"), List.of("p"), "bank.example", "https");
        assertEquals(Outcome.ENTRIES, planner.plan(https, CHROME, true, unlocked()).outcome());
    }

    @Test
    public void c1_spoofedBrowserPackageCannotClaimWebDomain() {
        // C-1：側載一個冒用 com.android.chrome 名稱的 App（簽署憑證不是 Google 的），自稱的網域不採信
        add("銀行", "https://bank.example/");
        FillPlanner spoofed = new FillPlanner(OWN, pkg -> java.util.Set.of("00".repeat(32)));
        FillPlan<String> plan = spoofed.plan(withDomain("bank.example"), CHROME, true, unlocked());
        assertEquals(Outcome.SEARCH_ONLY, plan.outcome());
        assertTrue(plan.entries().isEmpty());
        // 查不到憑證（沒有可見性或沒安裝）也一樣不採信
        assertEquals(Outcome.SEARCH_ONLY,
                new FillPlanner(OWN, Signers.NONE).plan(withDomain("bank.example"), CHROME, true, unlocked()).outcome());
        // 原廠版照常比對
        assertEquals(Outcome.ENTRIES, planner.plan(withDomain("bank.example"), CHROME, true, unlocked()).outcome());
    }

    @Test
    public void ac10_5_nonBrowserCannotClaimWebDomain() {
        // 資安總檢 H-1：webDomain 由請求的 App 自己提供（App 內 WebView、ViewStructure.setWebDomain），只有瀏覽器的才採信
        add("銀行", "https://bank.example/");
        EntryView bankApp = add("銀行 App", "androidapp://com.bank.app");
        FillPlan<String> spoof = planner.plan(withDomain("bank.example"), "com.evil.app", true, unlocked());
        assertEquals(Outcome.SEARCH_ONLY, spoof.outcome());
        assertTrue(spoof.entries().isEmpty());
        // 非瀏覽器帶網域時照一般 App 用套件名稱比對
        FillPlan<String> app = planner.plan(withDomain("bank.example"), "com.bank.app", true, unlocked());
        assertEquals(List.of(bankApp), app.entries());
    }

    @Test
    public void ac10_3_atMostTenAndSkipsEmptyEntries() {
        for (int i = 0; i < 12; i++) {
            add("Example " + i, "https://example.com/");
        }
        entries.add(entry("沒有帳密", "https://example.com/", "", ""));
        FillPlan<String> plan = planner.plan(withDomain("example.com"), CHROME, true, unlocked());
        assertEquals(10, plan.entries().size());
        assertTrue(plan.entries().stream().noneMatch(e -> e.title().equals("沒有帳密")));
    }

    @Test
    public void ac10_3_onlyEmptyEntriesMatchMeansSearch() {
        entries.add(entry("沒有帳密", "https://example.com/", "", ""));
        FillPlan<String> plan = planner.plan(withDomain("example.com"), CHROME, true, unlocked());
        assertEquals(Outcome.SEARCH_ONLY, plan.outcome());
    }

    @Test
    public void lockedVaultIsNotQueried() {
        add("Discord", "androidapp://com.discord");
        FillPlan<String> plan = planner.plan(login, "com.discord", true, Optional::empty);
        assertEquals(Outcome.LOCKED, plan.outcome());
        assertTrue(plan.entries().isEmpty());
    }

    /** T-31：上鎖（或還原備份）後，先前拿到的查詢物件 isOpen()=false、查詢為空——當作上鎖，不要變成「沒有符合」。 */
    @Test
    public void revokedLeaseCountsAsLocked() {
        add("Discord", "androidapp://com.discord");
        ModuleVaultAccess access = leased();
        Optional<UnlockedEntries> held = access.unlockedEntries();
        assertTrue(held.isPresent());
        access.revoke();
        FillPlan<String> plan = planner.plan(login, "com.discord", true, () -> held);
        assertEquals(Outcome.LOCKED, plan.outcome());
        assertTrue(plan.entries().isEmpty());
    }

    @Test
    public void disabledOwnAppOrNoFieldsGiveNothing() {
        add("Discord", "androidapp://com.discord");
        assertEquals(Outcome.NONE, planner.plan(login, "com.discord", false, unlocked()).outcome());
        assertEquals(Outcome.NONE, planner.plan(login, OWN, true, unlocked()).outcome());
        FieldScan<String> none = new FieldScan<>(List.of(), List.of(), null);
        assertEquals(Outcome.NONE, planner.plan(none, "com.discord", true, unlocked()).outcome());
        assertEquals(Outcome.NONE, planner.plan(login, null, true, unlocked()).outcome());
        assertEquals(0, queries);
    }

    @Test
    public void passwordOnlyFormStillMatches() {
        EntryView discord = add("Discord", "androidapp://com.discord");
        FieldScan<String> pinOnly = new FieldScan<>(List.of(), List.of("p"), null);
        assertEquals(List.of(discord), planner.plan(pinOnly, "com.discord", true, unlocked()).entries());
    }

    @Test
    public void planNeverPrintsEntryContent() {
        add("Discord", "androidapp://com.discord");
        String text = planner.plan(login, "com.discord", true, unlocked()).toString();
        assertTrue(text, !text.contains("secret") && !text.contains("Discord") && !text.contains("me@"));
    }

    private FieldScan<String> withDomain(String domain) {
        return new FieldScan<>(login.usernames(), login.passwords(), domain);
    }

    private VaultAccess unlocked() {
        return () -> {
            queries++;
            return leased().unlockedEntries();
        };
    }

    /** 和外殼相同的來源（T-31）：一份租約、發布目前條目的快照。 */
    private ModuleVaultAccess leased() {
        ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
        access.open().publish(ScreenData.of(entries, List.of(
                new GroupView(root, null, "根", "", false, Instant.EPOCH))));
        return access;
    }

    private EntryView add(String title, String url) {
        EntryView e = entry(title, url, "me@example.com", "secret");
        entries.add(e);
        return e;
    }

    private EntryView entry(String title, String url, String user, String password) {
        return new EntryView(UUID.randomUUID(), root, title, user, password, url, "", Map.of(), List.of(), "",
                Instant.EPOCH, Instant.EPOCH, 0);
    }
}
