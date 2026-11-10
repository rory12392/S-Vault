package app.svault.core.feature.health;

import app.svault.core.feature.health.HealthReportModel.Row;
import app.svault.core.module.ModulePreferences;
import app.svault.core.security.StrengthEstimator;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ScreenData;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.ScopedPreferences;
import app.svault.core.storage.Settings;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import java.io.IOException;
import java.text.MessageFormat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * 健檢報告狀態測試的共用準備（T-52、T-53 的兩份在 T-117 合成一份）：假資料條目、T-31 的唯讀保管庫、本機假 HIBP 伺服器、
 * 記錄日誌。UI 執行緒用一條單獨的執行緒模擬（模型的方法都經 {@link #onUi} 在它上面呼叫），背景計算用另一條。
 */
abstract class HealthModelFixture {

    static final Instant T = Instant.parse("2026-10-29T00:00:00Z");
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-29T06:30:00Z"), ZoneOffset.UTC);
    static final UUID ROOT = UUID.randomUUID();
    static final UUID WEB = UUID.randomUUID();
    static final UUID BIN = UUID.randomUUID();
    static final String WEAK = "password";
    static final String SHARED = "Tr0ub4dor&3-Horse-Battery-Staple";

    static WeakReuseAnalyzer analyzer;

    final EntryView bank = entry(WEB, "Bank", WEAK);
    final EntryView mail = entry(WEB, "Mail", SHARED);
    final EntryView shop = entry(WEB, "Shop", SHARED);
    final EntryView notes = entry(WEB, "Notes", "");
    final EntryView recycled = entry(BIN, "Old", WEAK);

    final ModuleVaultAccess access = new ModuleVaultAccess(() -> { });
    final LiveSettings live = LiveSettings.inMemory(Settings.defaults());
    final ModulePreferences prefs = new ScopedPreferences("health", live::current, live::update);
    final List<String> events = Collections.synchronizedList(new ArrayList<>());
    final List<String> logs = Collections.synchronizedList(new ArrayList<>());
    private final Handler logHandler = new Handler() {
        @Override
        public void publish(LogRecord r) {
            logs.add(MessageFormat.format(r.getMessage(), r.getParameters() == null
                    ? new Object[0] : r.getParameters()));
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    };
    ExecutorService ui;
    ExecutorService background;
    FakeHibp hibp;

    @BeforeAll
    static void loadDictionary() {
        analyzer = new WeakReuseAnalyzer(new StrengthEstimator());
    }

    @AfterAll
    static void dropDictionary() {
        analyzer = null;
    }

    @BeforeEach
    void setUp() throws IOException {
        ui = Executors.newSingleThreadExecutor();
        background = Executors.newSingleThreadExecutor();
        hibp = new FakeHibp();
        for (String name : List.of(HealthReportModel.class.getName(), BreachChecker.class.getName())) {
            Logger l = Logger.getLogger(name);
            l.setLevel(Level.ALL);
            l.addHandler(logHandler);
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        for (String name : List.of(HealthReportModel.class.getName(), BreachChecker.class.getName())) {
            Logger.getLogger(name).removeHandler(logHandler);
        }
        background.shutdownNow();
        ui.shutdownNow();
        hibp.close();
    }

    static EntryView entry(UUID group, String title, String password) {
        return new EntryView(UUID.randomUUID(), group, title, "user", password, "https://example.com", "",
                Map.of(), List.of(), "", T, T, 0);
    }

    static ScreenData data(EntryView... entries) {
        return ScreenData.of(List.of(entries), List.of(
                new GroupView(ROOT, null, "根", "", false, T),
                new GroupView(WEB, ROOT, "網站", "", false, T),
                new GroupView(BIN, ROOT, "資源回收桶", "", true, T)));
    }

    HealthReportModel model(Supplier<WeakReuseAnalyzer> a) {
        return model(a, hibp.baseUrl());
    }

    HealthReportModel model(Supplier<WeakReuseAnalyzer> a, String base) {
        HealthReportModel m = new HealthReportModel(access::unlockedEntries, a,
                () -> new BreachChecker(base, 2_000), background, ui, prefs, CLOCK);
        m.setListener(new HealthReportModel.Listener() {
            @Override
            public void changed() {
                events.add("changed:" + m.analysis() + "/" + m.breach());
            }

            @Override
            public void breachProgress(int done, int total) {
                events.add("progress:" + done + "/" + total);
            }
        });
        return m;
    }

    /** 解鎖並發布 Bank（弱）、Mail／Shop（重複）、Notes（沒有密碼）、回收桶裡的 Old。 */
    HealthReportModel unlockedModel() {
        access.open().publish(data(bank, mail, shop, notes, recycled));
        return model(() -> analyzer);
    }

    /** 在「主執行緒」上執行並取回結果。 */
    <V> V onUi(Callable<V> c) throws Exception {
        return ui.submit(c).get(10, TimeUnit.SECONDS);
    }

    void await(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!onUi(condition::getAsBoolean)) {
            if (System.nanoTime() > end) {
                throw new AssertionError("等待逾時：" + events);
            }
            Thread.sleep(10);
        }
    }

    static List<String> titles(List<Row> rows) {
        return rows.stream().map(Row::title).toList();
    }
}
