package app.svault.desktop.shell.start;

import app.svault.core.module.ModuleRegistry;
import app.svault.core.security.StrengthEstimator;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.LockSignalPort;
import app.svault.core.session.VaultSession;
import app.svault.core.shell.Secrets;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.VaultIoException;
import app.svault.desktop.platform.WindowsSessionLock;
import app.svault.desktop.shell.AppShell;
import app.svault.desktop.shell.ModuleMenu;
import app.svault.desktop.shell.ShellNavigator;
import app.svault.desktop.shell.VaultWorker;
import app.svault.desktop.shell.entries.ModuleVaultLink;
import app.svault.desktop.shell.entries.VaultScreen;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.settings.SettingsFlow;
import app.svault.desktop.ui.Toast;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 未解鎖時的畫面流程（US-01、US-06）：歡迎 → 建立／解鎖 → 解鎖後的主畫面，以及上鎖後回到解鎖畫面。
 *
 * <p>KDF 與檔案 I/O 一律在 {@link VaultWorker} 的單一背景執行緒執行，結果回到 JavaFX 執行緒更新畫面。
 * 主密碼從欄位取出後轉成位元組，
 * 背景工作結束時清零，欄位也立刻清空。
 */
public final class StartFlow implements WelcomeScreen.Actions, UnlockScreen.Actions {

    private static final Logger LOG = Logger.getLogger(StartFlow.class.getName());
    /** 超過這個時間改顯示 ProgressOverlay。 */
    private static final Duration SLOW = Duration.seconds(5);

    private final AppShell shell;
    private final VaultActions actions;
    private final StrengthEstimator estimator;
    private final FileDialogs dialogs;
    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.start.messages");
    private final VaultWorker worker = new VaultWorker();
    private final VaultScreen.Env env;
    private final RestoreFlow restore;
    private final CreateFlow createFlow;
    private final SettingsFlow settings;
    private final LockFlow lockFlow;
    private final ShellNavigator navigator;
    private final ModuleVaultLink moduleVault;

    private WelcomeScreen welcome;
    /** 解鎖後的三欄；上鎖時 {@code null}。 */
    private VaultScreen vault;
    private CreateScreen create;
    private UnlockScreen unlock;
    /** 上鎖後回到這個檔案的解鎖畫面。 */
    private Path lastFile;

    /**
     * @param shell     主視窗外框
     * @param actions   背後的操作
     * @param estimator 建立保管庫時的強度提示
     * @param dialogs   選檔對話框
     * @param env       條目畫面用到的資源
     * @param settings  設定與關於用到的資源（T-23）
     * @param lockSignal Windows 鎖定螢幕的訊號（T-18）
     * @param navigator 模組的條目導覽（解鎖時接上條目畫面，T-30）
     * @param moduleVault 模組的保管庫存取（解鎖時開租約並接上寫入、上鎖時收回，T-32、T-33）
     */
    StartFlow(AppShell shell, VaultActions actions, StrengthEstimator estimator, FileDialogs dialogs,
            VaultScreen.Env env, SettingsFlow.Env settings, LockSignalPort lockSignal, ShellNavigator navigator,
            ModuleVaultLink moduleVault) {
        this.shell = Objects.requireNonNull(shell, "shell");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.moduleVault = Objects.requireNonNull(moduleVault, "moduleVault");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
        this.dialogs = Objects.requireNonNull(dialogs, "dialogs");
        this.env = Objects.requireNonNull(env, "env");
        this.restore = new RestoreFlow(shell, worker, actions, text, () -> vault, this::showUnlocked);
        this.settings = new SettingsFlow(shell, settings, env.modules(), worker::execute, restore::openFromVault);
        this.createFlow = new CreateFlow(worker, actions, dialogs, text, () -> create, this::window,
                this::defaultFolder, this::created, this::showWelcome);
        this.lockFlow = new LockFlow(shell, worker, actions.session(), text, () -> vault, this::window,
                settings.settings(), env.clock(), lockSignal, env.clipboard(), moduleVault::revoke);
        actions.session().addListener(new VaultSession.Listener() {
            @Override
            public void onLocked() {
                // 不是從 LockFlow 開始的上鎖（例如還原失敗）也收回；可以在任何執行緒呼叫
                moduleVault.revoke();
                // 在背景執行緒上收到；畫面一律回到 JavaFX 執行緒
                Platform.runLater(StartFlow.this::showLocked);
            }
        });
    }

    /**
     * 建立正式程式用的流程（{@link VaultActions#standard}）。
     *
     * @param shell   主視窗外框
     * @param store   本機資料夾
     * @param dialogs 選檔對話框
     * @param modules desktop 功能模組（條目編輯畫面的擴充點）
     * @param browser 用系統瀏覽器開網址
     * @param live    執行中的設定（設定畫面改它，T-23）
     * @param navigator 模組拿到的條目導覽（{@code DesktopModules.hosts} 的同一個，T-30）
     * @param moduleVault 模組拿到的保管庫存取（{@code DesktopModules.hosts} 的同一份，T-32、T-33）
     * @param clipboard 剪貼簿（T-19；模組的 {@code ModuleHost.clipboard()} 包的同一個，T-34）
     * @return 流程（還沒顯示任何畫面，接著呼叫 {@link #start()}）
     */
    public static StartFlow create(AppShell shell, LocalStore store, FileDialogs dialogs,
            ModuleRegistry<DesktopFeature> modules, Consumer<String> browser, LiveSettings live,
            ShellNavigator navigator, ModuleVaultLink moduleVault, ClipboardGuard clipboard) {
        StrengthEstimator estimator = new StrengthEstimator();
        Clock clock = Clock.systemDefaultZone();
        VaultScreen.Env env = new VaultScreen.Env(clock, estimator::estimate, modules, clipboard, browser);
        SettingsFlow.Env settings = new SettingsFlow.Env(live, store.root(), SettingsFlow.Env.runtimeVersion(),
                clipboard::copy);
        StartFlow flow = new StartFlow(shell, VaultActions.standard(store, clock), estimator, dialogs, env,
                settings, WindowsSessionLock.forCurrentPlatform(), navigator, moduleVault);
        // zxcvbn 第一次評估要載入字典；先在背景做，使用者輸入第一個字時不會卡一下
        flow.worker.execute(() -> estimator.estimate("warm-up"));
        return flow;
    }

    /**
     * 第一個畫面：只有一筆最近開啟、而且檔案還在時直接進解鎖畫面（AC-06-3）；檔案不見了就在歡迎畫面說明。
     */
    public void start() {
        lockFlow.install();
        List<RecentItem> recent = actions.recent();
        if (recent.size() == 1) {
            RecentItem only = recent.get(0);
            if (Files.isRegularFile(only.file())) {
                showUnlock(only.file());
            } else {
                showWelcome();
                welcome.showProblem(OpenProblem.unreadable(VaultIoException.Reason.NOT_FOUND));
            }
            return;
        }
        showWelcome();
    }

    /**
     * 上鎖（{@code Ctrl+L}、工具列按鈕，AC-12-5；JavaFX 執行緒）：先關對話框、存檔再上鎖，見 {@link LockFlow}。
     */
    public void lock() {
        lockFlow.lock();
    }

    /**
     * 程式結束：上鎖（金鑰材料清零）後停止背景執行緒；最多等 5 秒（進行中的存檔要寫完）。
     */
    public void shutdown() {
        lock();
        lockFlow.stop();
        worker.shutdown();
    }

    /** 開發版畫面腳本用：模擬 Windows 鎖定螢幕的通知（不真的鎖定電腦）。 */
    public void simulateSessionLock() {
        lockFlow.simulateSessionLock();
    }

    /** @return 是否有背景工作進行中（開發版的畫面腳本等它結束再截圖） */
    public ReadOnlyBooleanProperty busyProperty() {
        return worker.busyProperty();
    }

    /**
     * 開發版畫面腳本用：卡住之後第 {@code n} 個背景工作（截「載入中」「儲存中」），見 {@link VaultWorker#holdBefore}。
     *
     * @param n 第幾個
     * @return 放行
     */
    public Runnable holdWorker(int n) {
        return worker.holdBefore(n);
    }

    // ---------- 畫面切換 ----------

    void showWelcome() {
        welcome = new WelcomeScreen(text, this);
        welcome.setRecent(actions.recent());
        show(welcome);
        focus(welcome::focusDefault);
    }

    void showUnlock(Path file) {
        lastFile = file;
        unlock = new UnlockScreen(text, file, this);
        show(unlock);
        focus(unlock::focusPassword);
    }

    private void showCreate() {
        create = new CreateScreen(text, estimator::estimate, createFlow);
        show(create);
        focus(create::focusDefault);
    }

    /** 解鎖後的三欄；還原後再呼叫一次，以新的內容重新載入。 */
    private void showUnlocked() {
        if (vault != null) {
            vault.dispose();
        }
        String name = RecentItem.displayName(lastFile);
        vault = new VaultScreen(shell, worker, actions.session(), name, env);
        // 每次解鎖（還原後重新載入也是）一份新的租約：舊的作廢，等待中的通知移到新的；寫入接上這次的保管庫（T-32、T-33）
        moduleVault.open(vault, worker, actions.session(), env.clock());
        vault.show();
        lockFlow.unlocked();
        navigator.attach(vault::openEntry);
        // DESIGN §4 的順序：產生器、健檢、匯入（功能模組的 MenuPage，T-30）、備份還原、設定、關於
        List<MenuItem> menu = new ArrayList<>(ModuleMenu.items(env.modules(), shell));
        menu.add(restore.menuItem());
        menu.addAll(settings.menuItems());
        shell.setMenuItems(menu);
        settings.attach();
        welcome = null;
        create = null;
        unlock = null;
    }

    private void showLocked() {
        navigator.detach();
        if (vault != null) {
            vault.dispose();
            vault = null;
        }
        lockFlow.locked();
        shell.setMenuItems(List.of());
        settings.detach();
        if (lastFile == null) {
            showWelcome();
            return;
        }
        showUnlock(lastFile);
        shell.toasts().show(Toast.Kind.INFO, text.getString("unlocked.locked"));
    }

    private void show(VBox screen) {
        shell.progress().hide();
        shell.showCentered(screen);
        // 置中畫面放在 ScrollPane 裡，內容要等 ScrollPane 的 skin 建立後才真的在場景中；先套用 CSS 與排版，
        // 接下來的 requestFocus 才有效
        shell.applyCss();
        shell.layout();
    }

    /**
     * 換畫面後的焦點。原本的焦點元件被移出場景時，JavaFX 會在下一個 pulse 把焦點交給第一個可聚焦的元件
     * （例如右下 Toast 的「關閉」）；所以換畫面後立刻設定，畫面第一次顯示後（啟動時）再設一次。
     */
    private static void focus(Runnable request) {
        request.run();
        Platform.runLater(request);
    }

    // ---------- 歡迎 ----------

    @Override
    public void openRecent(RecentItem item) {
        if (!Files.isRegularFile(item.file())) {
            welcome.showProblem(OpenProblem.unreadable(VaultIoException.Reason.NOT_FOUND));
            return;
        }
        showUnlock(item.file());
    }

    @Override
    public void forget(RecentItem item) {
        try {
            actions.forget(item);
            showWelcome();
            shell.toasts().show(Toast.Kind.INFO, MessageFormat.format(text.getString("welcome.removed"), item.name()));
        } catch (IOException e) {
            LOG.log(Level.WARNING, "無法更新最近開啟清單：{0}", e.getClass().getName());
            shell.toasts().show(Toast.Kind.ERROR, text.getString("welcome.removeFailed"));
        }
    }

    @Override
    public void openFile() {
        dialogs.open(window(), defaultFolder()).ifPresent(this::showUnlock);
    }

    @Override
    public void chooseAgain() {
        // 從原本的資料夾開始找（檔案被移動時通常還在附近）
        Path start = lastFile != null && lastFile.getParent() != null && Files.isDirectory(lastFile.getParent())
                ? lastFile.getParent() : defaultFolder();
        dialogs.open(window(), start).ifPresent(this::showUnlock);
    }

    @Override
    public void create() {
        showCreate();
    }

    @Override
    public void openDropped(Path file) {
        showUnlock(file);
    }

    // ---------- 建立（CreateFlow） ----------

    private void created(Path file) {
        lastFile = file;
        showUnlocked();
        shell.toasts().show(Toast.Kind.SUCCESS, text.getString("unlocked.created"));
    }

    // ---------- 解鎖 ----------

    @Override
    public void unlock() {
        unlock(false);
    }

    // costlyConfirmed：使用者看過「解鎖大約需要 n 秒」並按了仍要開啟（C-4，RestoreFlow 呼叫回來）
    private void unlock(boolean costlyConfirmed) {
        UnlockScreen screen = unlock;
        if (screen.restoreTarget() != null) {
            restore.restoreAndUnlock(screen);
            return;
        }
        char[] chars = screen.password();
        byte[] pw;
        try {
            pw = Secrets.utf8(chars);
        } finally {
            Secrets.wipe(chars);
        }
        Path file = screen.file();
        screen.setBusy(true);
        PauseTransition slow = new PauseTransition(SLOW);
        slow.setOnFinished(e -> shell.progress().show(text.getString("unlock.slow")));
        slow.play();
        worker.run(() -> {
            try {
                actions.unlock(file, pw, costlyConfirmed);
            } finally {
                Secrets.wipe(pw);
            }
        }, () -> {
            slow.stop();
            screen.clearPassword();
            shell.progress().hide();
            showUnlocked();
        }, e -> {
            slow.stop();
            shell.progress().hide();
            screen.setBusy(false);
            restore.showUnlockFailure(screen, e, () -> unlock(true));
        });
    }

    @Override
    public void restoreBackup() {
        restore.chooseForUnlock(unlock);
    }

    @Override
    public void openOther() {
        unlock.clearPassword();
        showWelcome();
    }

    // ---------- 共用 ----------

    private Window window() {
        return shell.getScene() == null ? null : shell.getScene().getWindow();
    }

    private Path defaultFolder() {
        return DriveFolders.find(FileSystems.getDefault().getRootDirectories()).orElse(null);
    }
}
