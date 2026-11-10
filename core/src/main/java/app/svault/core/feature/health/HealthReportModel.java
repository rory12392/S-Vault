package app.svault.core.feature.health;

import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryView;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 健檢報告頁的狀態：在背景執行緒算弱／重複與外洩檢查，結果轉成只有 UUID、名稱與原因的 {@link Row} 回到
 * UI 執行緒。兩個平台共用（T-117；原本 desktop 的 T-52 與 android 的 T-53 各有一份）。畫面只讀這裡的狀態，不碰
 * {@link EntryView}（含密碼），算完就放掉條目的參照。文字由畫面依 {@link Row#reason()} 與 {@link Row#count()} 組成，
 * 這裡不讀資源。
 *
 * <p>執行緒：{@link #analyze()}、{@link #checkBreach()}、{@link #close()} 與所有讀取都在 UI 執行緒（{@code ui}）上；
 * 計算交給 {@code background}，結果經 {@code ui} 回來。算完時保管庫已上鎖或還原過（{@link UnlockedEntries#isOpen()}
 * 為 false）、頁面已關，或之後又開始了新的一次，結果就丟掉（CONVENTIONS §11）。
 *
 * <p>日誌只記筆數與狀態，不記條目名稱與雜湊前綴（規格 §1）。
 */
public final class HealthReportModel {

    /** 上次外洩檢查完成的時間（epoch 毫秒；不存任何結果或雜湊，規格 §1「擁有的資料」）。兩個平台同一個鍵。 */
    public static final String KEY_LAST_BREACH_CHECK = "lastBreachCheck";

    private static final Logger LOG = Logger.getLogger(HealthReportModel.class.getName());

    /** 弱／重複分析的狀態。 */
    public enum Analysis {
        /** 還沒開始（或保管庫已上鎖） */
        IDLE,
        /** 背景計算中（ProgressOverlay） */
        RUNNING,
        /** 有結果 */
        READY,
        /** 計算時出錯（只記例外類型） */
        FAILED
    }

    /** 外洩檢查的狀態。 */
    public enum Breach {
        /** 這次開頁還沒檢查（摘要顯示「尚未檢查」） */
        NOT_CHECKED,
        /** 背景檢查中 */
        RUNNING,
        /** 完成（{@link #breachRows()} 可能是空的：沒有外洩） */
        COMPLETED,
        /** 連線失敗（AC-13-4）；弱／重複照常 */
        CONNECTION_FAILED
    }

    /** 列的原因。 */
    public enum Reason {
        /** 「強度：弱」 */
        WEAK,
        /** 「與 n 筆條目使用相同密碼」（n＝{@link Row#count()}） */
        REUSED,
        /** 「在外洩資料中出現 n 次」（n＝{@link Row#count()}） */
        BREACHED
    }

    /**
     * 報告的一列：條目的 UUID、名稱與原因。不含密碼。
     *
     * @param entryId 條目 UUID（「跳到條目」用）
     * @param title   條目名稱；空白時是空字串（畫面顯示「（沒有名稱）」）
     * @param reason  原因
     * @param count   重複時是共用的其他條目數、外洩時是次數；弱密碼是 0
     */
    public record Row(UUID entryId, String title, Reason reason, long count) {
        /** 只輸出 UUID：名稱也不進日誌（規格 §1）。 */
        @Override
        public String toString() {
            return "Row[" + entryId + "]";
        }
    }

    /** 狀態改變（在 UI 執行緒上呼叫）。 */
    public interface Listener {
        /** 任何狀態或結果改變 */
        void changed();

        /**
         * 外洩檢查的進度（在 UI 執行緒上呼叫）。
         *
         * @param done  已查完的前綴數
         * @param total 前綴總數
         */
        void breachProgress(int done, int total);
    }

    private static final Listener NONE = new Listener() {
        @Override
        public void changed() {
        }

        @Override
        public void breachProgress(int done, int total) {
        }
    };

    private final Supplier<Optional<UnlockedEntries>> entries;
    private final Supplier<WeakReuseAnalyzer> analyzer;
    private final Supplier<BreachChecker> checker;
    private final ExecutorService background;
    private final Executor ui;
    private final ModulePreferences preferences;
    private final Clock clock;
    private Listener listener = NONE;

    private Analysis analysis = Analysis.IDLE;
    private Breach breach = Breach.NOT_CHECKED;
    private int breachDone;
    private int breachTotal;
    private List<Row> weakRows = List.of();
    private List<List<Row>> reusedGroups = List.of();
    private int reusedCount;
    private List<Row> breachRows = List.of();
    /** 每次開始新的計算或關閉就加一；回來的結果代號不同就丟掉。 */
    private int analysisRun;
    private int breachRun;
    private Future<?> breachTask;
    private boolean closed;

    /**
     * @param entries     這次解鎖的唯讀查詢（{@code ModuleHost::unlockedEntries}）
     * @param analyzer    弱／重複分析（在背景執行緒第一次取用；建立時要載入字典）
     * @param checker     外洩檢查用戶端（正式版 {@code BreachChecker::new}，讀 {@code SVAULT_HIBP_BASE_URL}）
     * @param background  計算用的背景執行緒（關頁時以中斷取消外洩檢查）
     * @param ui          UI 執行緒（desktop 的 {@code Platform::runLater}、android 的主執行緒）
     * @param preferences 本模組的設定（記上次檢查外洩的時間）
     * @param clock       時鐘
     */
    public HealthReportModel(Supplier<Optional<UnlockedEntries>> entries, Supplier<WeakReuseAnalyzer> analyzer,
            Supplier<BreachChecker> checker, ExecutorService background, Executor ui, ModulePreferences preferences,
            Clock clock) {
        this.entries = entries;
        this.analyzer = analyzer;
        this.checker = checker;
        this.background = background;
        this.ui = ui;
        this.preferences = preferences;
        this.clock = clock;
    }

    /** @param l 監聽；android 的畫面重建時換成新的畫面，畫面拿掉時傳 {@code null} */
    public void setListener(Listener l) {
        listener = l == null ? NONE : l;
    }

    public Analysis analysis() {
        return analysis;
    }

    public Breach breach() {
        return breach;
    }

    /** @return 外洩檢查已查完的前綴數（檢查中才有意義；畫面重建時接著顯示進度） */
    public int breachDone() {
        return breachDone;
    }

    /** @return 外洩檢查的前綴總數（還不知道時是 0） */
    public int breachTotal() {
        return breachTotal;
    }

    /** @return 弱密碼（依名稱排序） */
    public List<Row> weakRows() {
        return weakRows;
    }

    /** @return 重複使用的密碼，一組一個清單（共用同一密碼的條目） */
    public List<List<Row>> reusedGroups() {
        return reusedGroups;
    }

    /** @return 重複使用密碼的條目數（各組加總） */
    public int reusedCount() {
        return reusedCount;
    }

    /** @return 外洩的條目與次數（依名稱排序）；還沒完成時為空 */
    public List<Row> breachRows() {
        return breachRows;
    }

    /** @return 弱與重複都沒有（分析完成後才有意義） */
    public boolean isClean() {
        return weakRows.isEmpty() && reusedGroups.isEmpty();
    }

    /**
     * 開始分析弱／重複密碼（AC-13-1）。已上鎖時停在 {@link Analysis#IDLE}。
     *
     * @return 有開始計算時為 true
     */
    public boolean analyze() {
        Optional<UnlockedEntries> now = entries.get();
        if (closed || !now.isPresent()) {
            return false;
        }
        UnlockedEntries q = now.get();
        List<EntryView> all = q.all();
        int run = ++analysisRun;
        analysis = Analysis.RUNNING;
        listener.changed();
        background.execute(() -> {
            WeakReuseReport report;
            try {
                report = analyzer.get().analyze(all);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "健檢分析失敗：{0}", e.getClass().getName());
                ui.execute(() -> {
                    if (run == analysisRun && !closed) {
                        analysis = Analysis.FAILED;
                        listener.changed();
                    }
                });
                return;
            }
            List<Row> weak = new ArrayList<>();
            for (EntryView e : report.weak()) {
                weak.add(new Row(e.uuid(), e.title(), Reason.WEAK, 0));
            }
            List<List<Row>> groups = new ArrayList<>();
            for (ReuseGroup g : report.reused()) {
                List<Row> rows = new ArrayList<>();
                for (EntryView e : g.entries()) {
                    rows.add(new Row(e.uuid(), e.title(), Reason.REUSED, g.othersCount(e.uuid())));
                }
                groups.add(List.copyOf(rows));
            }
            int reused = report.reusedEntryCount();
            ui.execute(() -> {
                if (run != analysisRun || closed) {
                    return; // 關頁或又開始了新的一次：丟掉結果
                }
                if (!q.isOpen()) {
                    // 中途上鎖或還原備份：結果屬於舊的內容。還原後還開著就用新的內容重算，上鎖時停在 IDLE
                    analysis = Analysis.IDLE;
                    if (!analyze()) {
                        listener.changed();
                    }
                    return;
                }
                weakRows = List.copyOf(weak);
                reusedGroups = List.copyOf(groups);
                reusedCount = reused;
                analysis = Analysis.READY;
                LOG.log(Level.INFO, "健檢分析完成：弱 {0} 筆、重複 {1} 筆", new Object[] {weak.size(), reused});
                listener.changed();
            });
        });
        return true;
    }

    /**
     * 開始外洩檢查（AC-13-2～AC-13-4）。畫面在使用者確認對話框後才呼叫。檢查中或已上鎖時不做事。
     *
     * @return 有開始檢查時為 true
     */
    public boolean checkBreach() {
        Optional<UnlockedEntries> now = entries.get();
        if (closed || breach == Breach.RUNNING || !now.isPresent()) {
            return false;
        }
        UnlockedEntries q = now.get();
        List<EntryView> all = q.all();
        int run = ++breachRun;
        breach = Breach.RUNNING;
        breachDone = 0;
        breachTotal = 0;
        breachRows = List.of();
        listener.changed();
        breachTask = background.submit(() -> {
            BreachReport report;
            try {
                report = checker.get().check(all, (done, total) -> ui.execute(() -> {
                    if (run == breachRun && !closed && breach == Breach.RUNNING) {
                        breachDone = done;
                        breachTotal = total;
                        listener.breachProgress(done, total);
                    }
                }));
            } catch (RuntimeException e) {
                // BreachChecker 本身不丟例外；這裡是意料外的錯誤（例如位址設定錯誤），當成這次檢查失敗
                LOG.log(Level.WARNING, "外洩檢查失敗：{0}", e.getClass().getName());
                ui.execute(() -> finishBreach(run, q, BreachReport.Status.CONNECTION_FAILED, List.of()));
                return;
            }
            List<Row> rows = new ArrayList<>();
            for (BreachHit hit : report.hits()) {
                rows.add(new Row(hit.entry().uuid(), hit.entry().title(), Reason.BREACHED, hit.count()));
            }
            ui.execute(() -> finishBreach(run, q, report.status(), rows));
        });
        return true;
    }

    private void finishBreach(int run, UnlockedEntries q, BreachReport.Status status, List<Row> rows) {
        if (run != breachRun || closed) {
            return;
        }
        breachTask = null;
        if (!q.isOpen()) {
            // 檢查中上鎖或還原：結果屬於舊的內容，不顯示
            breach = Breach.NOT_CHECKED;
            listener.changed();
            return;
        }
        switch (status) {
            case COMPLETED -> {
                breachRows = List.copyOf(rows);
                breach = Breach.COMPLETED;
                preferences.put(KEY_LAST_BREACH_CHECK, clock.millis());
            }
            case CONNECTION_FAILED -> breach = Breach.CONNECTION_FAILED;
            case CANCELLED -> breach = Breach.NOT_CHECKED;
            default -> throw new IllegalStateException("未知的外洩檢查狀態");
        }
        listener.changed();
    }

    /**
     * 頁面結束（desktop 離開頁面或上鎖、android 返回或上鎖時 Activity 結束）：中斷外洩檢查、之後回來的結果都丟掉，
     * 並放掉結果與監聽。
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        analysisRun++;
        breachRun++;
        if (breachTask != null) {
            breachTask.cancel(true);
            breachTask = null;
        }
        listener = NONE;
        weakRows = List.of();
        reusedGroups = List.of();
        breachRows = List.of();
    }
}
