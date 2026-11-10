package app.svault.desktop.shell;

import app.svault.core.module.ModuleRegistry;
import app.svault.core.session.ClipboardGuard;
import app.svault.core.shell.ModuleClipboards;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.ModuleVaultWriter;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.LogSetup;
import app.svault.core.storage.Settings;
import app.svault.core.storage.SettingsStore;
import app.svault.desktop.platform.DesktopClipboard;
import app.svault.desktop.platform.DesktopPaths;
import app.svault.desktop.platform.OwnerOnlyAcl;
import app.svault.desktop.shell.entries.ModuleVaultLink;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.gallery.DemoModules;
import app.svault.desktop.shell.gallery.ScreenDriver;
import app.svault.desktop.shell.gallery.ScriptedDialogs;
import app.svault.desktop.shell.gallery.Snapshot;
import app.svault.desktop.shell.gallery.UiGallery;
import app.svault.desktop.shell.start.FileDialogs;
import app.svault.desktop.shell.start.StartFlow;
import app.svault.desktop.ui.EmptyState;
import app.svault.desktop.ui.IconName;
import app.svault.desktop.ui.ThemeManager;
import app.svault.desktop.ui.ThemeMode;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Application;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/**
 * JavaFX 應用程式：主題、外框與視窗位置（T-02）；歡迎、建立、解鎖畫面與 {@code VaultSession}（T-07，{@link StartFlow}）。
 */
public final class SvaultApp extends Application {

    private static final Logger LOG = Logger.getLogger(SvaultApp.class.getName());

    private LocalStore store;
    private StartFlow flow;
    // 模組設定的寫檔（T-22）用自己的執行緒：不排在保管庫的存檔後面（同 android 的 svault-settings）
    private final ExecutorService settingsWriter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "svault-settings");
        t.setDaemon(true);
        return t;
    });

    @Override
    public void start(Stage stage) {
        if (DevDataDir.mustRefuse(Boolean.getBoolean(DevOptions.DEV_PROPERTY),
                System.getProperty(LocalStore.DATA_DIR_KEY), System.getenv(LocalStore.DATA_DIR_KEY))) {
            System.err.println(DevDataDir.MESSAGE);
            Platform.exit();
            return;
        }
        try {
            store = LocalStore.open(DesktopPaths.defaultDataDir());
            LogSetup.install(store);
            if (store.root().equals(DesktopPaths.defaultDataDir())) {
                // 預設的 %APPDATA%\S-Vault：只有自己與 SYSTEM 讀得到（T-116；自訂的 SVAULT_DATA_DIR 不動）
                OwnerOnlyAcl.apply(store.root());
            }
        } catch (IOException e) {
            // 日誌失敗不阻止啟動
            System.err.println("無法建立本機資料夾或日誌：" + e.getClass().getName());
        }
        DevOptions dev = DevOptions.parse(getParameters().getRaw(), Boolean.getBoolean(DevOptions.DEV_PROPERTY));
        ResourceBundle messages = ResourceBundle.getBundle("app.svault.desktop.shell.messages");

        // 主題：開發參數 --theme 優先，否則讀 settings.json（沒有設定或讀不到時跟隨系統）
        LiveSettings live = store == null ? LiveSettings.inMemory(Settings.defaults())
                : LiveSettings.load(new SettingsStore(store.settingsFile()));
        Settings settings = live.current();
        ThemeMode mode = dev.theme() != null ? dev.theme() : ThemeMode.fromSetting(settings.theme());
        ThemeManager theme = new ThemeManager(mode, Platform.getPreferences().colorSchemeProperty());
        // 設定畫面改了主題就當場切換（立即生效）
        // 開發參數 --theme 一直有效：模組寫自己的設定也會通知這裡，不能中途換掉截圖的主題（T-52）
        live.addListener(now -> theme.modeProperty().set(dev.theme() != null ? dev.theme()
                : ThemeMode.fromSetting(now.theme())));

        AppShell shell = new AppShell();
        stage.getIcons().add(new Image("/app/svault/desktop/shell/app-icon.png"));
        Scene scene = dev.fixedSize() ? new Scene(shell, dev.width(), dev.height()) : new Scene(shell);
        // 一定要在 show() 之前套用，否則會先閃一下淺色
        theme.track(scene);

        ScriptedDialogs scripted = dev.drive().isEmpty() ? null : new ScriptedDialogs();
        if (dev.gallery()) {
            stage.setTitle(messages.getString("gallery.title"));
            shell.bindShortcuts(scene, () -> LOG.info("上鎖（展示頁）"));
            new UiGallery(shell, theme, dev.galleryState()).install();
        } else if (store == null) {
            // 沒有本機資料夾就沒有最近開啟、備份與基準檔，不能安全地開啟保管庫
            stage.setTitle(messages.getString("app.title"));
            shell.bindShortcuts(scene, () -> { });
            shell.showCentered(new EmptyState(IconName.ERROR, messages.getString("app.title"),
                    messages.getString("error.dataDir"), null, null));
        } else {
            stage.setTitle(messages.getString("app.title"));
            ShellNavigator navigator = new ShellNavigator();
            // 解鎖後條目畫面一定會開始載入，所以模組在載入中要內容時不用另外觸發（T-32）；寫入入口同一份租約（T-33）
            ModuleVaultLink moduleVault = new ModuleVaultLink(new ModuleVaultAccess(() -> { }), new ModuleVaultWriter());
            // 外殼與模組共用一個剪貼簿（T-19、T-34）：同樣的清除秒數，上鎖時一起清
            ClipboardGuard clipboard = new ClipboardGuard(new DesktopClipboard(), DesktopClipboard.scheduler(),
                    live::current);
            ModuleRegistry<DesktopFeature> modules = DesktopModules.discover(settings, (id, action, error) ->
                    LOG.log(Level.WARNING, "功能模組出錯：{0} {1} {2}",
                            new Object[] {id, action, error.getClass().getName()}),
                    DesktopModules.hosts(live, settingsWriter, navigator, moduleVault.access(), moduleVault.writer(),
                            id -> ModuleClipboards.forModule(id, clipboard, Platform::isFxApplicationThread)),
                    dev.demoModules() ? DemoModules.all(moduleVault.writer()) : List.of());
            HostServices host = getHostServices();
            flow = StartFlow.create(shell, store, scripted != null ? scripted : FileDialogs.system(), modules,
                    host::showDocument, live, navigator, moduleVault, clipboard);
            shell.bindShortcuts(scene, flow::lock);
            flow.start();
        }
        stage.setScene(scene);
        if (dev.fixedSize()) {
            stage.setMinWidth(AppShell.MIN_WIDTH);
            stage.setMinHeight(AppShell.MIN_HEIGHT);
            stage.setResizable(false);
        } else {
            new WindowPlacement(store == null ? null : new WindowStateStore(store.root())).attach(stage);
        }
        stage.setOnShown(e -> logStartup());
        stage.show();
        if (flow != null && scripted != null) {
            new ScreenDriver(scene, flow.busyProperty(), flow::holdWorker, scripted, flow::simulateSessionLock,
                    dev.drive()).start();
        } else if (dev.snapshot() != null) {
            Snapshot.saveAndExit(dev.snapshot());
        }
    }

    @Override
    public void stop() {
        if (flow != null) {
            flow.shutdown();
        }
        // 讓排著的模組設定寫完再結束（AtomicFiles：真的被中斷也不會留下壞檔）
        settingsWriter.shutdown();
        try {
            settingsWriter.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LogSetup.uninstall();
    }

    /** 效能基準：從行程啟動到主視窗顯示的時間，寫進日誌。 */
    private static void logStartup() {
        Platform.runLater(() -> ProcessHandle.current().info().startInstant().ifPresent(start -> {
            long ms = Duration.between(start, Instant.now()).toMillis();
            LOG.log(Level.INFO, "desktop 已啟動：啟動到主視窗顯示 {0} ms", Long.toString(ms));
        }));
    }
}
