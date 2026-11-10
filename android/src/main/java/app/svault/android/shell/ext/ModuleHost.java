package app.svault.android.shell.ext;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.MasterPasswordCheck;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * 外殼給 Android 功能模組的服務（T-22），由 App 的 {@code Application} 實作。模組的畫面（{@code MenuPage}、
 * {@code SettingsSection} 的 Fragment）、按鈕動作與 AF 的 {@code AutofillService} 都是系統或外殼建立的，
 * 拿不到模組實例，所以一律從 {@link Context} 取：{@code ModuleHost.of(requireContext()).preferences("generator")}。
 *
 * <p>之後的核心任務要再給模組服務時加在這裡。保管庫只給唯讀查詢（{@link #unlockedEntries()}，T-31）與整批新增條目
 * （{@link #addEntries}，T-33，只開放給匯入模組），不給 {@code VaultSession}；剪貼簿只能放進內容（{@link #clipboard}，T-34）。
 * T-35 加目前解鎖的保管庫 ID（{@link #unlockedVaultId()}）與確認主密碼（{@link #verifyMasterPassword}，只開放給生物辨識模組）；
 * 停用通知在 {@link AndroidFeature#onDisabled}。T-36 加開模組自己頁面的入口（{@link #pageIntent}、{@link #pageSender}），
 * 上鎖時先解鎖再回到頁面。
 */
public interface ModuleHost {

    /**
     * @param context 任何屬於這個 App 的 Context
     * @return 外殼服務
     * @throws ClassCastException App 的 {@code Application} 沒有實作本介面（不會發生在正式程式）
     */
    static ModuleHost of(Context context) {
        return (ModuleHost) context.getApplicationContext();
    }

    /**
     * @param moduleId 呼叫端模組自己的代號（只傳自己的：每個模組只讀寫自己的 {@code modules.<id>.*}，CONVENTIONS §11）
     * @return 該模組的設定；在主執行緒上改，寫檔由外殼放到背景執行緒
     * @throws IllegalArgumentException 代號格式不對
     */
    ModulePreferences preferences(String moduleId);

    /**
     * 以 UUID 開啟條目詳細（T-30；HLT 的「跳到條目」）：條目詳細疊在目前的畫面上，返回回到模組頁。條目不存在時在
     * {@code context} 的畫面上顯示提示、已上鎖時不做事，規則見 {@link EntryNavigator}。
     *
     * @param context 目前的畫面（模組的 Fragment 傳 {@code requireActivity()}）；不是 Activity 時另開工作
     * @return 導覽服務（綁在這個畫面上，不要存到畫面結束之後）
     */
    EntryNavigator navigator(Context context);

    /**
     * 開啟中保管庫的唯讀查詢（T-31；AF 的 {@code SVaultAutofillService}）：只有查詢，不能寫入、拿不到主密碼。
     * 任何執行緒都可以呼叫，不做 I/O。
     *
     * <p>上鎖時為空——包含 App 行程被系統結束後重新啟動（例如系統為了自動填入請求叫起服務）：解鎖狀態只在記憶體，重啟後
     * 一律是上鎖。解鎖了但內容還在載入（剛解鎖、還沒到首頁）時也為空，並開始載入；要等的話用
     * {@link #whenEntriesLoaded}。拿到的物件綁在這次解鎖，上鎖或還原備份後一律回傳空的結果，規則見
     * {@link UnlockedEntries}。
     *
     * @return 這次解鎖的查詢；上鎖或還在載入時為空
     */
    Optional<UnlockedEntries> unlockedEntries();

    /**
     * 等這次解鎖的內容載入好再通知一次（T-31；例如 AF 的填入流程從解鎖畫面回來後），已載入時立即排進 {@code executor}。
     * 通知後用 {@link #unlockedEntries()} 取得查詢。
     *
     * @param executor 執行通知的地方（畫面傳 {@code context.getMainExecutor()}）
     * @param callback 通知（模組自己包 {@code ModuleGuard}；丟出的例外只記錄類型）
     * @return 會通知時為 {@code true}；已上鎖時為 {@code false}。等待中上鎖就不通知（畫面本來就會導回解鎖畫面）
     */
    boolean whenEntriesLoaded(Executor executor, Runnable callback);

    /**
     * 寫入條目（T-33；IMP 的匯入精靈）：在根群組底下新建群組 {@link EntryBatch#groupName()}、加入整批條目、存檔一次。
     * 全部成功才算數，失敗、有衝突或已上鎖時一筆都不加入；結果一定剛好通知一次。任何執行緒都可以呼叫，不等存檔。
     * 成功後條目畫面照常換新內容，{@link #unlockedEntries()} 下一次查詢就看得到新條目。規則和 desktop 的同名方法相同。
     *
     * <p>只開放給匯入模組（D-162，{@code ModuleVaultWriter.ALLOWED_MODULES}），其他代號丟 {@link SecurityException}。
     * 預設實作一直是上鎖（通知 {@link EntryBatchResult.Status#LOCKED}），測試用的假 host 不用改。
     *
     * @param moduleId 呼叫端模組自己的代號（同 {@link #preferences(String)}，只傳自己的）
     * @param batch    要加入的條目
     * @param executor 執行通知的地方（畫面傳 {@code context.getMainExecutor()}）
     * @param callback 結果（模組自己包 {@code ModuleGuard}；丟出的例外只記錄類型）
     * @throws SecurityException 這個模組不能寫入
     */
    default void addEntries(String moduleId, EntryBatch batch, Executor executor,
                            Consumer<EntryBatchResult> callback) {
        executor.execute(() -> callback.accept(EntryBatchResult.rejected(EntryBatchResult.Status.LOCKED)));
    }

    /**
     * 剪貼簿（T-34；GEN 產生器頁的「複製」）：和條目畫面「複製密碼」同一個 {@code ClipboardGuard}，清除秒數、上鎖時立即清
     * （切到背景而上鎖時照原本的秒數清，D-138）、敏感內容標記（{@code EXTRA_IS_SENSITIVE}）都相同；模組讀不到內容、不能改
     * 秒數。只在主執行緒呼叫它的方法，規則見 {@link ModuleClipboard}。規則和 desktop 的 {@code clipboard()} 相同。
     *
     * <p>預設實作為空（測試用的假 host 不用改）；正式程式的 host（{@code SvaultApplication}）一定有。
     *
     * @param moduleId 呼叫端模組自己的代號（同 {@link #preferences(String)}，只傳自己的；只用在日誌）
     * @return 這個模組的剪貼簿；沒有時模組不顯示「複製」
     * @throws IllegalArgumentException 代號格式不對
     */
    default Optional<ModuleClipboard> clipboard(String moduleId) {
        return Optional.empty();
    }

    /**
     * 目前解鎖的保管庫 ID（T-35；BIO 的 Keystore 別名 {@code svault_bio_<ID>}、私有偏好 {@code biometric.<ID>}）：根群組 UUID 的
     * 字串，和解鎖畫面交給 {@link UnlockProvider} 的 {@code vaultId} 是同一個。任何執行緒都可以呼叫，不做 I/O。
     *
     * <p>按下上鎖當下、還原備份後到重新接上之前、還沒解鎖過時為空（同 {@link #unlockedEntries()} 的租約）。預設實作為空
     * （測試用的假 host 不用改）。所有模組都能用：ID 不是機密（{@code recent.json} 也有）。
     *
     * @return 目前解鎖的保管庫 ID；上鎖時為空
     */
    default Optional<String> unlockedVaultId() {
        return Optional.empty();
    }

    /**
     * 模組要開系統的檔案選擇器之前呼叫（D-192；IMP 的「選擇 CSV」）：背景上鎖設成「立即」時，這次離開最多寬限 2 分鐘，
     * 選完回來不會已經上鎖、選的檔案也不會作廢。回到前景就恢復原本的規則；螢幕關閉照樣立即上鎖。在主執行緒呼叫。
     * 預設實作不做事（測試用的假 host）。
     */
    default void expectExternalPicker() {
    }

    /**
     * 確認使用者輸入的是不是目前解鎖的保管庫的主密碼（T-35；BIO 在設定中啟用前再輸入一次主密碼）。和開啟中保管庫的複合金鑰
     * 比對，不重算 KDF、幾乎不花時間；外殼不保留也不交出主密碼，規則見 {@code ModuleVaultIdentity}（D-165）。任何執行緒都可以
     * 呼叫，不等比對；結果一定剛好通知一次。
     *
     * <p>{@code password} 在呼叫回傳前就算完、不會被保留：之後呼叫端可以立即清零，或繼續用（BIO 拿去加密），用完自己清零。
     * 結果是 {@link MasterPasswordCheck.Status#MATCH} 時帶確認的保管庫 ID，要用它（不是先前另外取的）命名金鑰。同一次解鎖錯
     * 5 次後一律 {@link MasterPasswordCheck.Status#TOO_MANY_ATTEMPTS}。
     *
     * <p>只開放給生物辨識模組（{@code ModuleVaultIdentity.VERIFY_MODULES}），其他代號丟 {@link SecurityException}。預設實作一直
     * 是上鎖（通知 {@link MasterPasswordCheck.Status#LOCKED}），測試用的假 host 不用改。
     *
     * @param moduleId 呼叫端模組自己的代號（同 {@link #preferences(String)}，只傳自己的）
     * @param password 使用者輸入的主密碼（UTF-8）
     * @param executor 執行通知的地方（畫面傳 {@code context.getMainExecutor()}）
     * @param callback 結果（模組自己包 {@code ModuleGuard}；丟出的例外只記錄類型）
     * @throws SecurityException 這個模組不能確認主密碼
     */
    default void verifyMasterPassword(String moduleId, byte[] password, Executor executor,
                                      Consumer<MasterPasswordCheck> callback) {
        executor.execute(() -> callback.accept(MasterPasswordCheck.rejected(MasterPasswordCheck.Status.LOCKED)));
    }

    /**
     * 開模組自己的頁面（T-36；外殼的頁面框架載入 {@code page}，同 {@code MenuPage}：Top app bar＝標題＋返回、
     * {@code FLAG_SECURE}、建立在 {@code ModuleGuard} 裡）。模組用 {@code startActivity} 或 Activity Result API 開；頁面要交回
     * 結果時直接 {@code requireActivity().setResult(…)} 再 {@code finish()}。
     *
     * <p><b>上鎖時</b>（和首頁選單開的頁面不同）：開啟時已上鎖 → 在同一個工作裡先開解鎖畫面，解鎖成功後回到頁面才建立
     * Fragment；返回、「開啟其他檔案」時頁面以 {@code RESULT_CANCELED} 結束。顯示中被上鎖 → 頁面只以 {@code RESULT_CANCELED}
     * 結束自己。兩種都不清掉工作、不開首頁。
     *
     * <p>預設實作為空（測試用的假 host 不用改）；正式程式的 host 一定有。所有模組都能用。
     *
     * @param moduleId 呼叫端模組自己的代號（同 {@link #preferences(String)}；錯誤紀錄用）
     * @param titleRes 頁面標題（模組自己的字串資源，不能是 0）
     * @param page     頁面（public、有 public 無參數建構子；不是時頁面顯示錯誤狀態）
     * @param args     給頁面的參數（{@code getArguments()}）；不放主密碼與條目內容
     * @return 開啟頁面的 Intent
     * @throws IllegalArgumentException 代號格式不對、{@code titleRes} 是 0
     */
    default Optional<Intent> pageIntent(String moduleId, @StringRes int titleRes, Class<? extends Fragment> page,
                                        @Nullable Bundle args) {
        return Optional.empty();
    }

    /**
     * 同 {@link #pageIntent}，包成給系統代為開啟的 {@link IntentSender}（T-36；AF 的
     * {@code FillResponse.Builder#setAuthentication}）。系統開的頁面在請求來源 App 的工作裡，所以上鎖時的回程特別重要：解鎖
     * 後頁面直接交回結果，使用者回到原本的 App。
     *
     * <p>是 {@code FLAG_MUTABLE} 的 {@code PendingIntent}（自動填入的官方文件要求，系統要補驗證參數），元件明確指定、頁面的
     * 參數送出端蓋不掉。
     *
     * @param moduleId    呼叫端模組自己的代號
     * @param titleRes    頁面標題
     * @param page        頁面
     * @param args        給頁面的參數
     * @param requestCode 這次的代號：相同代號共用一個 PendingIntent（參數換成最新的），要並存時用不同代號
     * @return 開啟頁面的 IntentSender；預設實作為空
     * @throws IllegalArgumentException 同 {@link #pageIntent}
     */
    default Optional<IntentSender> pageSender(String moduleId, @StringRes int titleRes,
                                              Class<? extends Fragment> page, @Nullable Bundle args, int requestCode) {
        return Optional.empty();
    }
}
