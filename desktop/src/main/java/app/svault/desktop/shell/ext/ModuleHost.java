package app.svault.desktop.shell.ext;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 外殼給一個 desktop 功能模組的服務（T-22），由 {@link DesktopFeature#attach(ModuleHost)} 交給模組。
 * 每個模組拿到自己的一份：{@link #preferences()} 只讀寫該模組的 {@code modules.<id>.*}。
 *
 * <p>之後的核心任務要再給模組服務時加在這裡，模組的擴充點簽章不用改。保管庫只給唯讀查詢（{@link #unlockedEntries()}，
 * T-32，和 android 的同名方法規則相同），不給 {@code VaultSession}、{@code VaultEditor} 與主密碼；寫入只有整批新增條目
 * （{@link #addEntries}，T-33，只開放給匯入模組）。剪貼簿只能放進內容（{@link #clipboard()}，T-34）。
 */
public interface ModuleHost {

    /**
     * @return 這個模組自己的設定；在 JavaFX 執行緒上改，寫檔由外殼放到背景執行緒
     */
    ModulePreferences preferences();

    /**
     * 以 UUID 開啟條目詳細（T-30；HLT 的「跳到條目」）：關掉模組頁、回到三欄並選到那筆條目。條目不存在時顯示提示、
     * 已上鎖時不做事，規則見 {@link EntryNavigator}。attach 時就能拿，但只有解鎖後呼叫才有作用。
     *
     * @return 導覽服務（整個程式共用一個，可以存起來）
     */
    EntryNavigator navigator();

    /**
     * 開啟中保管庫的唯讀查詢（T-32；HLT 的健檢報告、IMP 的重複判定）：只有查詢，不能寫入、拿不到主密碼。
     * 任何執行緒都可以呼叫（JavaFX 執行緒或模組自己的背景執行緒），不做 I/O、不等存檔。
     *
     * <p>上鎖時為空；解鎖了但條目畫面還在載入（剛解鎖、還原後重新載入）時也為空，要等的話用 {@link #whenEntriesLoaded}。
     * 拿到的物件綁在這次解鎖，上鎖（按下上鎖當下）或還原備份後一律回傳空的結果、不丟例外，再解鎖也不恢復；
     * 每次用之前重新取，不要存在欄位裡。規則見 {@link UnlockedEntries}。
     *
     * <p>預設實作是「一直上鎖」：外殼以外的實作（測試用的假 host）不用跟著改；正式程式的 host 一定覆寫。
     *
     * @return 這次解鎖的查詢；上鎖或還在載入時為空
     */
    default Optional<UnlockedEntries> unlockedEntries() {
        return Optional.empty();
    }

    /**
     * 等這次解鎖的內容載入好再通知一次（T-32；例如模組頁一打開就要算，但解鎖後條目畫面還在載入），已載入時立即排進
     * {@code executor}。通知後用 {@link #unlockedEntries()} 取得查詢。
     *
     * <p>預設實作同 {@link #unlockedEntries()}：一直上鎖，回傳 {@code false}。
     *
     * @param executor 執行通知的地方（畫面傳 {@code Platform::runLater}）
     * @param callback 通知（模組自己包 {@code ModuleGuard}；丟出的例外只記錄類型）
     * @return 會通知時為 {@code true}；已上鎖時為 {@code false}。等待中上鎖就不通知（模組頁本來就會被解鎖畫面換掉）
     */
    default boolean whenEntriesLoaded(Executor executor, Runnable callback) {
        return false;
    }

    /**
     * 寫入條目（T-33；IMP 的匯入精靈）：在根群組底下新建群組 {@link EntryBatch#groupName()}、加入整批條目、存檔一次。
     * 全部成功才算數，失敗、有衝突或已上鎖時一筆都不加入；結果一定剛好通知一次。任何執行緒都可以呼叫，不等存檔。
     * 成功後外殼照常重畫三欄與同步狀態，{@link #unlockedEntries()} 下一次查詢就看得到新條目。
     *
     * <p>只開放給匯入模組（D-162，{@code ModuleVaultWriter.ALLOWED_MODULES}），其他模組呼叫時丟 {@link SecurityException}。
     * 預設實作同 {@link #unlockedEntries()}：一直上鎖，通知 {@link EntryBatchResult.Status#LOCKED}。
     *
     * @param batch    要加入的條目
     * @param executor 執行通知的地方（畫面傳 {@code Platform::runLater}）
     * @param callback 結果（模組自己包 {@code ModuleGuard}；丟出的例外只記錄類型）
     * @throws SecurityException 這個模組不能寫入
     */
    default void addEntries(EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback) {
        executor.execute(() -> callback.accept(EntryBatchResult.rejected(EntryBatchResult.Status.LOCKED)));
    }

    /**
     * 剪貼簿（T-34；GEN 產生器頁的「複製」）：和三欄「複製密碼」同一個 {@code ClipboardGuard}，清除秒數、上鎖時立即清、
     * 不進 Windows 剪貼簿歷程記錄都相同；模組讀不到內容、不能改秒數。只在 JavaFX 執行緒呼叫它的方法，規則見
     * {@link ModuleClipboard}。上鎖與否都能用（上鎖時模組頁已被解鎖畫面換掉）。
     *
     * <p>預設實作為空（外殼以外的實作，例如測試用的假 host，不用跟著改）；正式程式的 host 一定有。
     *
     * @return 這個模組的剪貼簿（attach 時就能拿，可以存起來）；沒有時模組不顯示「複製」
     */
    default Optional<ModuleClipboard> clipboard() {
        return Optional.empty();
    }
}
