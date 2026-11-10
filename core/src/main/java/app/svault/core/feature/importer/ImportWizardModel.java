package app.svault.core.feature.importer;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryDraft;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * 「從 Chrome 匯入」精靈的狀態：1 選擇檔案 → 2 預覽 → 3 完成。兩個平台共用（T-117；原本 desktop 的
 * T-61 與 android 的 T-62 各有一份）；不碰畫面類別，頁面只顯示與轉送使用者的操作。android 放在 Activity 範圍的
 * ViewModel，旋轉、換主題、改字體時接著用。
 *
 * <p>讀檔與解析在 {@code background}，結果回到 {@code ui}（UI 執行緒）；狀態只在 {@code ui} 上改，監聽也在那裡通知。寫入只經
 * {@link ImportHost#addEntries}（T-33：外殼新建「Chrome 匯入」群組、全部加入、存檔一次，失敗時一筆都不留）。
 *
 * <p>CSV 的明文只在記憶體：不寫暫存檔、不記日誌（日誌只有例外類型與原因代碼）；匯入完成、重新開始或頁面結束時
 * 放掉解析結果的參照。預覽的列（{@link PreviewRow}）沒有密碼欄位，畫面只能顯示遮罩（AC-14-1）。
 */
public final class ImportWizardModel {

    private static final Logger LOG = Logger.getLogger(ImportWizardModel.class.getName());
    /** 錯誤邊界記錄用的模組代號（同兩個平台的匯入模組 {@code ID}）。 */
    private static final String MODULE_ID = "importer";

    /** 精靈的步驟。 */
    public enum Step {
        /** 1 選擇檔案。 */
        CHOOSE,
        /** 2 預覽。 */
        PREVIEW,
        /** 3 完成。 */
        DONE
    }

    /** 正在背景做的事（畫面顯示 ProgressOverlay）。 */
    public enum Busy {
        NONE, READING, IMPORTING
    }

    /** 畫面要顯示的錯誤（error Banner）；讀檔的錯誤留在第 1 步，匯入的錯誤留在第 2 步。 */
    public enum Problem {
        /** 檔案讀不到（不存在、權限被收回、I/O 錯誤）。 */
        READ_FAILED,
        /** 空檔或只有空白行。 */
        EMPTY,
        /** 不是 Chrome 的密碼 CSV（缺必要欄位）。 */
        MISSING_COLUMNS,
        /** 不是 UTF-8。 */
        NOT_UTF8,
        /** 超過 16 MiB。 */
        TOO_LARGE,
        /** 已上鎖（模組頁本來就會被解鎖畫面換掉，仍給一個說明）。 */
        LOCKED,
        /** 保管庫有衝突要先處理，沒有匯入。 */
        CONFLICT,
        /** 存檔失敗，沒有匯入。 */
        SAVE_FAILED
    }

    /**
     * 預覽的一列。刻意沒有密碼：畫面一律顯示遮罩，也不透露長度（AC-14-1）。
     *
     * @param line      CSV 的行號
     * @param name      條目名稱（{@code name} 空白時是備案名稱，同匯入後的名稱）
     * @param username  帳號
     * @param url       網址
     * @param duplicate 標示「可能重複」（AC-14-4）
     */
    public record PreviewRow(int line, String name, String username, String url, boolean duplicate) {

        /** 只輸出行號，不含內容（CONVENTIONS §2）。 */
        @Override
        public String toString() {
            return "PreviewRow[line=" + line + ", duplicate=" + duplicate + "]";
        }
    }

    private final ImportHost host;
    private final ModuleGuard guard;
    private final Executor background;
    private final Executor ui;
    private Runnable listener;

    private Step step = Step.CHOOSE;
    private Busy busy = Busy.NONE;
    private Problem problem;
    private String name = "";
    private String location = "";
    private ImportPlan plan;
    private boolean skipDuplicates = true;
    private int imported;
    /** 每次選檔、重新開始、關頁就加一：晚到的背景結果對不上就丟掉。 */
    private int generation;

    /**
     * @param host       外殼服務（唯讀查詢與寫入入口）
     * @param guard      模組的錯誤邊界
     * @param background 讀檔與解析的執行緒
     * @param ui         改狀態與通知的執行緒（desktop 的 {@code Platform::runLater}、android 的主執行緒）
     */
    public ImportWizardModel(ImportHost host, ModuleGuard guard, Executor background, Executor ui) {
        this.host = host;
        this.guard = guard;
        this.background = background;
        this.ui = ui;
    }

    /**
     * @param listener 狀態改變時在 {@code ui} 上通知；android 的頁面重建時換新的，{@code null}＝頁面已經不在
     */
    public void setListener(Runnable listener) {
        this.listener = listener;
    }

    /**
     * 選了檔案：等保管庫內容載入好，在背景讀檔、解析並比對重複，完成後到第 2 步；整個檔案不能匯入時留在第 1 步並顯示原因。
     *
     * @param csv 使用者選的檔案
     */
    public void open(CsvSource csv) {
        if (busy != Busy.NONE || step == Step.DONE) {
            return;
        }
        int gen = ++generation;
        name = "";
        location = "";
        plan = null;
        problem = null;
        step = Step.CHOOSE;
        busy = Busy.READING;
        changed();
        boolean waiting = host.whenEntriesLoaded(background, () -> {
            if (!guard.run(MODULE_ID, "read", () -> read(gen, csv))) {
                ui.execute(() -> failed(gen, Problem.READ_FAILED));
            }
        });
        if (!waiting) {
            failed(gen, Problem.LOCKED);
        }
    }

    /** 背景執行緒：讀檔、解析、比對重複。 */
    private void read(int gen, CsvSource csv) {
        ChromeCsv parsed;
        try (InputStream in = csv.open()) {
            parsed = ChromeCsvParser.read(in);
        } catch (ChromeCsvException e) {
            ui.execute(() -> failed(gen, problemOf(e.kind())));
            return;
        } catch (IOException | SecurityException e) {
            // 只記類型：路徑、Uri、檔名與內容都不寫日誌。android 的提供者收回權限時丟 SecurityException
            LOG.log(Level.WARNING, "無法讀取匯入的 CSV：error={0}", e.getClass().getName());
            ui.execute(() -> failed(gen, Problem.READ_FAILED));
            return;
        }
        Optional<UnlockedEntries> entries = host.unlockedEntries();
        if (!entries.isPresent()) {
            ui.execute(() -> failed(gen, Problem.LOCKED));
            return;
        }
        // 資源回收桶裡的條目不算重複（all() 不含回收桶）
        ImportPlan result = ImportPlan.of(parsed, entries.get().all());
        String shownName = csv.name();
        String shownLocation = csv.location();
        ui.execute(() -> planned(gen, result, shownName, shownLocation));
    }

    private void planned(int gen, ImportPlan result, String shownName, String shownLocation) {
        if (gen != generation) {
            return;
        }
        plan = result;
        name = shownName;
        location = shownLocation;
        skipDuplicates = true;
        busy = Busy.NONE;
        step = Step.PREVIEW;
        changed();
    }

    private void failed(int gen, Problem kind) {
        if (gen != generation) {
            return;
        }
        busy = Busy.NONE;
        problem = kind;
        if (kind != Problem.CONFLICT && kind != Problem.SAVE_FAILED) {
            // 讀檔的錯誤：回到第 1 步，舊的預覽不留
            plan = null;
            step = Step.CHOOSE;
        }
        changed();
    }

    /**
     * 可能重複的列要略過（預設）或照樣匯入（AC-14-4）。
     *
     * @param value {@code true}＝略過
     */
    public void setSkipDuplicates(boolean value) {
        if (skipDuplicates != value) {
            skipDuplicates = value;
            changed();
        }
    }

    /**
     * 「匯入到『Chrome 匯入』群組」：整批交給外殼，結果回來前顯示匯入中。成功到第 3 步；失敗留在第 2 步並說明，
     * 保管庫不變（T-33）。
     */
    public void importNow() {
        if (step != Step.PREVIEW || busy != Busy.NONE) {
            return;
        }
        Optional<EntryBatch> batch = plan.batch(skippedLines());
        if (!batch.isPresent()) {
            return;
        }
        int gen = generation;
        busy = Busy.IMPORTING;
        problem = null;
        changed();
        boolean sent = guard.run(MODULE_ID, "import", () -> host.addEntries(batch.get(), ui,
                r -> guard.run(MODULE_ID, "imported", () -> imported(gen, r))));
        if (!sent) {
            failed(gen, Problem.SAVE_FAILED);
        }
    }

    private void imported(int gen, EntryBatchResult result) {
        if (gen != generation) {
            // 頁面已結束或重新開始：寫入照常完成（外殼會重畫條目畫面），這裡不用再顯示
            return;
        }
        switch (result.status()) {
            case SAVED -> {
                imported = result.added();
                // 匯入完成就放掉明文
                plan = null;
                busy = Busy.NONE;
                step = Step.DONE;
                changed();
            }
            case LOCKED -> failed(gen, Problem.LOCKED);
            case CONFLICT -> failed(gen, Problem.CONFLICT);
            case FAILED -> failed(gen, Problem.SAVE_FAILED);
            default -> throw new IllegalStateException("未知的結果：" + result.status());
        }
    }

    /** 「重新選擇檔案」「匯入另一個檔案」：回到第 1 步，放掉上一個檔案的內容。 */
    public void restart() {
        generation++;
        step = Step.CHOOSE;
        busy = Busy.NONE;
        problem = null;
        plan = null;
        name = "";
        location = "";
        imported = 0;
        changed();
    }

    /**
     * 頁面結束（desktop 離開頁面；android 返回、上鎖時整個工作換成解鎖畫面）：放掉明文、丟掉晚到的結果、拿掉監聽，不通知。
     */
    public void clear() {
        generation++;
        plan = null;
        name = "";
        location = "";
        busy = Busy.NONE;
        listener = null;
    }

    public Step step() {
        return step;
    }

    public Busy busy() {
        return busy;
    }

    public Optional<Problem> problem() {
        return Optional.ofNullable(problem);
    }

    /** @return 選的檔案名稱（預覽顯示來源）；還沒選時是空字串 */
    public String name() {
        return name;
    }

    /** @return 選的檔案位置（第 3 步提示刪除時顯示，AC-14-3）；還沒選時是空字串 */
    public String location() {
        return location;
    }

    /** @return 可以匯入的筆數（「共 n 筆」） */
    public int total() {
        return plan == null ? 0 : plan.size();
    }

    /** @return 前 10 筆（AC-14-1） */
    public List<PreviewRow> preview() {
        if (plan == null) {
            return List.of();
        }
        List<PreviewRow> out = new ArrayList<>();
        for (ImportCandidate c : plan.preview()) {
            EntryDraft draft = c.row().toDraft();
            out.add(new PreviewRow(c.row().line(), draft.title(), c.row().username(), c.row().url(),
                    c.possibleDuplicate()));
        }
        return Collections.unmodifiableList(out);
    }

    /** @return 格式錯誤而跳過的行號（AC-14-2） */
    public List<Integer> skippedFormatLines() {
        if (plan == null) {
            return List.of();
        }
        // core 要符合 Android API 29：沒有 Stream.toList()
        return Collections.unmodifiableList(plan.skipped().stream().map(SkippedLine::line)
                .collect(Collectors.toList()));
    }

    /** @return 可能重複的列的行號（AC-14-4） */
    public List<Integer> duplicateLines() {
        if (plan == null) {
            return List.of();
        }
        return Collections.unmodifiableList(plan.candidates().stream().filter(ImportCandidate::possibleDuplicate)
                .map(c -> c.row().line()).collect(Collectors.toList()));
    }

    public boolean skipDuplicates() {
        return skipDuplicates;
    }

    /** @return 按下「匯入」時會加入的筆數 */
    public int toImport() {
        return total() - (skipDuplicates ? duplicateLines().size() : 0);
    }

    /** @return 第 3 步的「已匯入 n 筆」 */
    public int imported() {
        return imported;
    }

    private Set<Integer> skippedLines() {
        return skipDuplicates ? new HashSet<>(duplicateLines()) : Set.of();
    }

    private static Problem problemOf(ChromeCsvException.Kind kind) {
        return switch (kind) {
            case EMPTY -> Problem.EMPTY;
            case MISSING_COLUMNS -> Problem.MISSING_COLUMNS;
            case NOT_UTF8 -> Problem.NOT_UTF8;
            case TOO_LARGE -> Problem.TOO_LARGE;
        };
    }

    private void changed() {
        Runnable l = listener;
        if (l != null) {
            l.run();
        }
    }
}
