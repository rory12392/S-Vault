package app.svault.android;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.fragment.app.Fragment;
import app.svault.android.platform.AndroidClipboard;
import app.svault.android.platform.ContentResolverDocuments;
import app.svault.android.platform.DocumentAccess;
import app.svault.android.platform.ScreenOffSignal;
import app.svault.android.shell.AndroidModules;
import app.svault.android.shell.AutoLockCallbacks;
import app.svault.android.shell.BackgroundLock;
import app.svault.android.shell.LockGate;
import app.svault.android.shell.ModulePages;
import app.svault.android.shell.entries.EntriesModel;
import app.svault.android.shell.entries.EntriesSlot;
import app.svault.android.shell.entries.ModuleEntriesFeed;
import app.svault.android.shell.entries.ModuleEntryNavigator;
import app.svault.android.shell.entries.ModuleLeases;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.shell.start.StartActions;
import app.svault.android.ui.ThemeMode;
import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.MasterPasswordCheck;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.security.StrengthEstimator;
import app.svault.core.session.AutoLock;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.BackupStore;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.LogSetup;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.ScopedPreferences;
import app.svault.android.shell.settings.SettingsChanges;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import app.svault.core.storage.SettingsStore;
import app.svault.core.shell.ModuleClipboards;
import app.svault.core.vault.VaultCodec;
import java.io.File;
import java.io.IOException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Android 的進入點：指定 StAX 實作、建立本機資料夾與日誌、依 {@code settings.json} 套用主題、
 * 建立解鎖狀態（{@link VaultSession}）並讓 {@link LockGate} 跟著它。
 */
public class SvaultApplication extends Application implements ModuleHost {

    private static final String TAG = "SVault";

    static {
        // Android 沒有內建 StAX，ServiceLoader 也找不到 Woodstox 的註冊：在任何 XML 解析之前以系統屬性指定
        // （Architecture §10、STACK §13）
        System.setProperty("javax.xml.stream.XMLInputFactory", "com.ctc.wstx.stax.WstxInputFactory");
        System.setProperty("javax.xml.stream.XMLOutputFactory", "com.ctc.wstx.stax.WstxOutputFactory");
        System.setProperty("javax.xml.stream.XMLEventFactory", "com.ctc.wstx.stax.WstxEventFactory");
    }

    private final LockGate lockGate = new LockGate();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "svault-worker"));
    private DocumentAccess documents;
    private LocalStore localStore;
    private VaultSession vaultSession;
    private StartActions startActions;
    private ModuleRegistry<AndroidFeature> modules;
    private BackgroundLock backgroundLock;
    private StrengthEstimator strength;
    private ClipboardGuard clipboard;
    private Clock clock;
    private final EntriesSlot<EntriesModel> entries = new EntriesSlot<>(
            () -> vaultSession != null && vaultSession.isUnlocked(), this::newEntries, EntriesModel::dispose);
    // 模組的唯讀查詢（T-31）、寫入（T-33）、保管庫 ID 與確認主密碼（T-35）：一次解鎖一份租約，上鎖時一起失效。
    // 主執行緒用 lambda：欄位初始化時 Application 還沒接上 Context，不能先取 getMainExecutor()
    private final ModuleLeases moduleLeases = new ModuleLeases(this::loadForModules,
            r -> getMainExecutor().execute(r), entries::peek);
    // 寫 settings.json 用自己的執行緒：不排在保管庫的存檔與合併後面（存檔可能要好幾秒，期間 App 被關掉設定就沒寫到）
    private final ExecutorService settingsWriter = Executors.newSingleThreadExecutor(
            r -> new Thread(r, "svault-settings"));
    private SettingsChanges settingsChanges;

    @Override
    public void onCreate() {
        super.onCreate();
        documents = new ContentResolverDocuments(getContentResolver());
        try {
            localStore = LocalStore.open(new File(getFilesDir(), "svault").toPath());
            LogSetup.install(localStore);
        } catch (IOException e) {
            // 日誌失敗不阻止啟動；只記類型
            Log.w(TAG, "無法建立本機資料夾或日誌：" + e.getClass().getName());
        }
        // 執行中的設定（T-24）：設定畫面改了立即生效，T-18（自動上鎖）、T-19（剪貼簿）從這裡讀最新值。
        // 沒有本機資料夾時只在記憶體（不寫檔）
        LiveSettings live = localStore == null ? LiveSettings.inMemory(Settings.defaults())
                : LiveSettings.load(new SettingsStore(localStore.settingsFile()));
        settingsChanges = new SettingsChanges(live, settingsWriter, getMainExecutor());
        // 複製密碼後依設定的秒數清除（T-19；每次複製讀當下的設定；行程被結束時由鬧鐘清，T-28）
        clipboard = new ClipboardGuard(new AndroidClipboard(this), AndroidClipboard.scheduler(this), live::current);
        // 在任何 Activity 建立前套用主題，不會先閃淺色（design-impl §1）。讀不到設定時跟隨系統
        Settings settings = live.current();
        AppCompatDelegate.setDefaultNightMode(ThemeMode.fromSetting(settings.theme()).nightMode());
        // 設定畫面在主執行緒改主題：當場切換（模式相同時 AppCompat 不重建畫面）
        live.addListener(now -> AppCompatDelegate.setDefaultNightMode(ThemeMode.fromSetting(now.theme()).nightMode()));

        modules = AndroidModules.discover(settings, (moduleId, action, error) -> { });
        // 停用中的模組清掉這台裝置上的資料（T-35，BIO 刪金鑰）：排在任何保管庫操作之前，不佔主執行緒
        ModuleRegistry<AndroidFeature> discovered = modules;
        worker.execute(() -> AndroidModules.notifyDisabled(discovered, this));

        if (localStore != null) {
            VaultCodec codec = new VaultCodec();
            RecentStore recent = new RecentStore(localStore.recentFile());
            // 備份檔名用手機的當地時間（備份清單顯示給使用者看，AC-09-2）
            clock = Clock.systemDefaultZone();
            SaveCoordinator saver = new SaveCoordinator(codec, localStore, recent, clock);
            vaultSession = new VaultSession(codec, saver, recent, clock);
            // 解鎖、上鎖可能在背景執行緒發生；LockGate 只在主執行緒改變狀態
            vaultSession.addListener(lockGate.sessionListener(getMainExecutor()));
            // 條目畫面的狀態屬於一次解鎖：上鎖時丟掉（快照裡有條目內容），下次解鎖重新建立
            vaultSession.addListener(entries::drop);
            // 模組的唯讀查詢（T-31）也是一次解鎖一份：在通知的執行緒當場切換，不等主執行緒
            vaultSession.addListener(new VaultSession.Listener() {
                @Override
                public void onUnlocked() {
                    moduleLeases.open(vaultSession, worker, clock);
                }

                @Override
                public void onLocked() {
                    moduleLeases.revoke();
                }
            });
            startActions = new StartActions(codec, saver, vaultSession, recent,
                    new BackupStore(localStore.backupsDir(), clock), documents);
            installAutoLock(live);
        }
    }

    /**
     * 自動上鎖（T-18；AC-12-2）：所有畫面停止（切到背景）後滿設定的秒數、或螢幕關閉時上鎖。「目前有解鎖」看主執行緒上的
     * {@link LockGate}，不問背景存檔時被鎖住的 session。
     */
    private void installAutoLock(LiveSettings live) {
        AutoLockCallbacks callbacks = new AutoLockCallbacks();
        // 切到背景而上鎖時不清剪貼簿：使用者正要把密碼貼到別的 App，照原本的秒數清除（T-19 使用者決定）
        BackgroundLock background = new BackgroundLock(clock, () -> AutoLock.androidBackgroundLimit(live.current()),
                () -> !lockGate.isLocked(), () -> lock(false), callbacks);
        backgroundLock = background;
        callbacks.attach(background);
        registerActivityLifecycleCallbacks(callbacks);
        // 螢幕關閉：已經上鎖（例如剛因切到背景上鎖）時剪貼簿也要清（AC-05-4）
        new ScreenOffSignal(this).start(() -> {
            clipboard.clearNow();
            background.screenOff();
        });
    }

    /**
     * 上鎖（主執行緒；Top app bar 的上鎖按鈕）：見 {@link #lock(boolean)}，S-Vault 複製的密碼立即清除（AC-05-4）。
     */
    public void lockNow() {
        lock(true);
    }

    /**
     * 上鎖（主執行緒；手動上鎖與自動上鎖都走這裡）：先由條目畫面的狀態記下編輯中沒儲存的內容與衝突的選擇，在背景
     * 執行緒存檔後上鎖（AC-12-3、AC-08-5）；畫面立即導回解鎖畫面（{@link LockGate}），不等存檔，背景的內容不會再顯示。
     *
     * @param clearClipboard S-Vault 複製、還沒清掉的密碼要不要立即清除（切到背景而上鎖時不清）
     */
    private void lock(boolean clearClipboard) {
        if (clearClipboard) {
            clipboard.clearNow();
        }
        // 模組立即拿不到內容，不等背景存檔完成、session 真的上鎖（T-31）
        moduleLeases.revoke();
        if (vaultSession == null) {
            return;
        }
        EntriesModel current = entries.peek();
        if (current != null) {
            current.lock();
        } else {
            worker.execute(vaultSession::lock);
        }
        lockGate.lock();
    }

    /**
     * 保管庫操作（解鎖、建立、存檔、上鎖）的背景執行緒（Architecture §5）：只有一條，所以上鎖會排在進行中的
     * 解鎖或存檔之後，不會在序列化途中清掉金鑰。
     *
     * @return 單一執行緒的 Executor
     */
    public ExecutorService worker() {
        return worker;
    }

    /**
     * 解鎖期間條目畫面共用的狀態（內容快照、存檔中、尚未同步）。
     *
     * @return 目前這次解鎖的狀態；沒有解鎖時為 {@code null}
     */
    public EntriesModel entries() {
        return entries.get();
    }

    private EntriesModel newEntries() {
        EntriesModel model = new EntriesModel(vaultSession, worker, clock);
        // 解鎖通知和這裡誰先都可以，最後都接在最新的租約上（不拿兩把鎖的原因見 ModuleLeases.open）
        moduleLeases.feed(model);
        return model;
    }

    /** 模組要內容但還沒載入（{@code ModuleVaultAccess} 的 loader；任何執行緒）：在主執行緒開始載入。 */
    private void loadForModules() {
        getMainExecutor().execute(() -> {
            EntriesModel model = lockGate.isLocked() ? null : entries();
            if (model != null) {
                ModuleEntriesFeed.load(model);
            }
        });
    }

    /**
     * 還原後（T-12）：開啟中的保管庫已換成還原的版本，丟掉舊的條目狀態，下一個條目畫面以新的內容重新建立。
     */
    public void resetEntries() {
        entries.drop();
        // 模組手上的舊查詢屬於還原前的內容：作廢，換一份新的（T-31）
        if (vaultSession != null && vaultSession.isUnlocked()) {
            moduleLeases.open(vaultSession, worker, clock);
        } else {
            moduleLeases.revoke();
        }
    }

    /**
     * 設定畫面的變更與執行中的設定（{@link SettingsChanges#live()}；T-18、T-19 讀
     * {@code autoLockAndroidSeconds()}、{@code clipboardClearSeconds()} 或註冊監聽，在主執行緒收到）。
     *
     * @return 設定
     */
    public SettingsChanges settingsChanges() {
        return settingsChanges;
    }

    /** @return 剪貼簿（複製密碼後依設定的秒數清除，T-19） */
    public ClipboardGuard clipboard() {
        return clipboard;
    }

    /**
     * @return 歡迎、建立、解鎖畫面背後的操作；本機資料夾建立失敗時為 {@code null}
     */
    public StartActions startActions() {
        return startActions;
    }

    /** @return 功能模組（解鎖畫面的 {@code UnlockProvider} 等擴充點） */
    public ModuleRegistry<AndroidFeature> modules() {
        return modules;
    }

    /**
     * 只給儀器測試：換成測試用的模組（APK 裡的真模組都是空殼，測不到模組段落的錯誤狀態；T-22）。之後開的畫面才看得到。
     *
     * @param replacement 測試用的模組；{@code null} 時重新探索 APK 裡的模組
     */
    @VisibleForTesting
    public void replaceModules(ModuleRegistry<AndroidFeature> replacement) {
        modules = replacement != null ? replacement
                : AndroidModules.discover(settingsChanges.current(), (moduleId, action, error) -> { });
    }

    /**
     * 模組自己的設定（T-22）：改了立即生效，經 {@link SettingsChanges} 在 {@code svault-settings} 執行緒寫檔，
     * 和設定畫面的變更走同一條路。寫檔失敗只記日誌（設定照常生效到 App 結束）。
     */
    @Override
    public ModulePreferences preferences(String moduleId) {
        return new ScopedPreferences(moduleId, settingsChanges::current,
                change -> settingsChanges.change(change, () -> { }));
    }

    /**
     * 模組的條目導覽（T-30）：「已上鎖」看主執行緒上的 {@link LockGate}，不問背景存檔時被鎖住的 session。
     */
    @Override
    public EntryNavigator navigator(Context context) {
        return new ModuleEntryNavigator(context, () -> lockGate.isLocked() ? null : entries());
    }

    /** 模組的唯讀查詢（T-31）：規則見 {@link ModuleHost#unlockedEntries()}、{@code ModuleVaultAccess}。 */
    @Override
    public Optional<UnlockedEntries> unlockedEntries() {
        return moduleLeases.access().unlockedEntries();
    }

    @Override
    public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
        return moduleLeases.access().whenLoaded(executor, callback);
    }

    /** 模組寫入條目（T-33）：規則見 {@link ModuleHost#addEntries}、{@code ModuleVaultWriter}。 */
    @Override
    public void addEntries(String moduleId, EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback) {
        moduleLeases.writer().addEntries(moduleId, batch, executor, callback);
    }

    @Override // 開系統選擇器前（D-192）：規則見 ModuleHost
    public void expectExternalPicker() {
        Optional.ofNullable(backgroundLock).ifPresent(BackgroundLock::expectExternalPicker);
    }

    /** 目前解鎖的保管庫 ID（T-35）：規則見 {@link ModuleHost#unlockedVaultId()}。 */
    @Override
    public Optional<String> unlockedVaultId() {
        return moduleLeases.identity().vaultId();
    }

    /** 確認主密碼（T-35，只給 BIO）：規則見 {@link ModuleHost#verifyMasterPassword}、{@code ModuleVaultIdentity}。 */
    @Override
    public void verifyMasterPassword(String moduleId, byte[] password, Executor executor,
                                     Consumer<MasterPasswordCheck> callback) {
        moduleLeases.identity().verifyMasterPassword(moduleId, password, executor, callback);
    }

    /** 模組的剪貼簿（T-34）：包的是條目畫面用的同一個 {@link #clipboard()}，規則見 {@link ModuleHost#clipboard}。 */
    @Override
    public Optional<ModuleClipboard> clipboard(String moduleId) {
        return Optional.of(ModuleClipboards.forModule(moduleId, clipboard, () -> Looper.myLooper() == getMainLooper()));
    }

    /** 模組開自己的頁面（T-36）：規則見 {@link ModuleHost#pageIntent}、{@link ModulePages}。 */
    @Override
    public Optional<Intent> pageIntent(String moduleId, int titleRes, Class<? extends Fragment> page, Bundle args) {
        return Optional.of(ModulePages.intent(this, moduleId, titleRes, page, args));
    }

    @Override
    public Optional<IntentSender> pageSender(String moduleId, int titleRes, Class<? extends Fragment> page,
                                             Bundle args, int requestCode) {
        return Optional.of(ModulePages.sender(this, moduleId, titleRes, page, args, requestCode));
    }

    /**
     * zxcvbn 的字典在第一次評估時載入（約幾百毫秒），所以整個 App 共用一個，建立保管庫畫面開啟時先在背景暖機。
     *
     * @return 密碼強度評估
     */
    public synchronized StrengthEstimator strength() {
        if (strength == null) {
            strength = new StrengthEstimator();
        }
        return strength;
    }

    /** @return 解鎖狀態；本機資料夾建立失敗時為 {@code null}（無法存檔，也就不提供保管庫功能） */
    public VaultSession vaultSession() {
        return vaultSession;
    }

    /** @return 檔案選擇器給的 Uri 的存取（建立 {@code SafVaultLocation} 用） */
    public DocumentAccess documents() {
        return documents;
    }

    /** @return 本機資料夾；建立失敗時為 {@code null} */
    public LocalStore localStore() {
        return localStore;
    }

    /** @return 保管庫的上鎖狀態（基底 Activity 依此導回解鎖畫面） */
    public LockGate lockGate() {
        return lockGate;
    }
}
