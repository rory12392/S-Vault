package app.svault.android.feature.autofill;

import static app.svault.android.feature.autofill.FakeNode.field;
import static app.svault.android.feature.autofill.FakeNode.group;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.android.feature.autofill.FillPlan.Outcome;
import app.svault.core.module.ModuleGuard;
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
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * 服務的進入點（不含 Android 型別的部分）：錯誤邊界、停用、日誌只記來源套件與是否符合。
 */
public class AutofillRequestsTest {

    private final List<String> failures = new ArrayList<>();
    private final List<LogRecord> logged = new ArrayList<>();
    private final AutofillRequests requests = new AutofillRequests(
            new ModuleGuard((id, action, error) -> failures.add(id + ":" + action)), "app.svault.android",
            FillPlannerTest.GENUINE);
    private final Logger logger = Logger.getLogger(AutofillRequests.class.getName());
    private final Handler capture = new Handler() {
        @Override
        public void publish(LogRecord record) {
            logged.add(record);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };

    private final UUID root = UUID.randomUUID();
    private final FakeNode screen = group(field("u").hints("username"), field("p").hints("password"))
            .webDomain("github.com");

    @Before
    public void captureLog() {
        logger.addHandler(capture);
    }

    @After
    public void releaseLog() {
        logger.removeHandler(capture);
    }

    @Test
    public void ac10_3_matchingRequest() {
        Optional<FillPlan<String>> plan = requests.handle("com.android.chrome", List.of(screen), () -> true,
                vaultWith("https://github.com/"));
        assertEquals(Outcome.ENTRIES, plan.orElseThrow().outcome());
        assertEquals(1, plan.orElseThrow().entries().size());
        assertTrue(failures.isEmpty());
    }

    @Test
    public void logOnlyHasPackageAndWhetherMatched() {
        requests.handle("com.android.chrome", List.of(screen), () -> true, vaultWith("https://github.com/"));
        requests.handle("com.android.chrome", List.of(screen), () -> true, vaultWith("https://gitlab.com/"));
        assertEquals(2, logged.size());
        for (LogRecord r : logged) {
            String all = r.getMessage() + " " + List.of(r.getParameters());
            assertTrue(all, all.contains("com.android.chrome"));
            for (String secret : List.of("github", "gitlab", "me@example.com", "secret", "GitHub")) {
                assertFalse(all, all.contains(secret));
            }
        }
        assertEquals(List.of("com.android.chrome", true), List.of(logged.get(0).getParameters()));
        assertEquals(List.of("com.android.chrome", false), List.of(logged.get(1).getParameters()));
    }

    @Test
    public void disabledGivesNothing() {
        Optional<FillPlan<String>> plan = requests.handle("com.android.chrome", List.of(screen), () -> false,
                vaultWith("https://github.com/"));
        assertEquals(Outcome.NONE, plan.orElseThrow().outcome());
    }

    /** 故障注入：任何一步丟例外都被邊界接住，回傳空（服務回傳 null，不提供建議）並通知。 */
    @Test
    @SuppressWarnings("unchecked")
    public void faultsAreContained() {
        assertFalse(requests.handle("com.discord", List.of(screen), () -> true, () -> {
            throw new IllegalStateException("保管庫在比對途中上鎖");
        }).isPresent());
        assertFalse(requests.handle("com.discord", List.of(screen), () -> {
            throw new IllegalArgumentException("設定讀不到");
        }, vaultWith("x")).isPresent());
        AfNode<String> broken = (AfNode<String>) java.lang.reflect.Proxy.newProxyInstance(
                AfNode.class.getClassLoader(), new Class<?>[] {AfNode.class}, (proxy, method, args) -> {
                    throw new IllegalStateException("節點已回收");
                });
        assertFalse(requests.handle("com.discord", List.of(broken), () -> true, vaultWith("x")).isPresent());
        assertEquals(List.of("autofill:fillRequest", "autofill:fillRequest", "autofill:fillRequest"), failures);
    }

    /** AC-10-2：解鎖畫面回來後，用驗證項目帶來的欄位、套件與網域重新比對，只給符合的條目。 */
    @Test
    public void ac10_2_afterUnlockGetsMatchingEntries() {
        FieldScan<String> fields = new FieldScan<>(List.of("u"), List.of("p"), "github.com");
        FillPlan<String> plan = requests.afterUnlock(fields, "com.android.chrome", () -> true,
                vaultWith("https://github.com/")).orElseThrow();
        assertEquals(Outcome.ENTRIES, plan.outcome());
        assertEquals(fields, plan.fields());
        assertEquals(List.of("com.android.chrome", true), List.of(logged.get(0).getParameters()));
    }

    /** AC-10-5：解鎖後才比對，不符的一樣不給帳密；還沒載入好（或又上鎖）時是上鎖、停用時什麼都不給。 */
    @Test
    public void ac10_5_afterUnlockMismatchLockedOrDisabledGiveNoCredentials() {
        FieldScan<String> fields = new FieldScan<>(List.of("u"), List.of("p"), "evilgithub.com");
        assertEquals(Outcome.SEARCH_ONLY, requests.afterUnlock(fields, "com.android.chrome", () -> true,
                vaultWith("https://github.com/")).orElseThrow().outcome());
        assertEquals(Outcome.LOCKED, requests.afterUnlock(fields, "com.android.chrome", () -> true,
                VaultAccess.LOCKED).orElseThrow().outcome());
        assertEquals(Outcome.NONE, requests.afterUnlock(fields, "com.android.chrome", () -> false,
                vaultWith("https://evilgithub.com/")).orElseThrow().outcome());
    }

    /** 故障注入：驗證流程的回填也在邊界裡（動作 authResult）。 */
    @Test
    public void afterUnlockFaultsAreContained() {
        FieldScan<String> fields = new FieldScan<>(List.of("u"), List.of("p"), null);
        assertFalse(requests.afterUnlock(fields, "com.discord", () -> true, () -> {
            throw new IllegalStateException("保管庫在比對途中上鎖");
        }).isPresent());
        assertEquals(List.of("autofill:authResult"), failures);
    }

    private VaultAccess vaultWith(String url) {
        EntryView e = new EntryView(UUID.randomUUID(), root, "GitHub", "me@example.com", "secret", url, "", Map.of(),
                List.of(), "", Instant.EPOCH, Instant.EPOCH, 0);
        ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
        access.open().publish(ScreenData.of(List.of(e),
                List.of(new GroupView(root, null, "根", "", false, Instant.EPOCH))));
        return access::unlockedEntries;
    }
}
