package app.svault.desktop.feature.importer;

import app.svault.core.feature.importer.ImportHost;
import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.UnlockedEntries;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.ModuleHost;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.scene.Node;

/**
 * 匯入（IMP，US-14）的 desktop 模組類別（T-61）：註冊選單頁「從 Chrome 匯入」（{@link MenuPage}），內容是三步的
 * 匯入精靈（{@link ImportWizardPage}）。重複判定用外殼的唯讀查詢（T-32），寫入用外殼的整批寫入入口
 * （T-33，{@link ModuleHost#addEntries}），模組拿不到 {@code VaultEditor} 與主密碼。
 */
public final class ImporterDesktopFeature implements DesktopFeature {

    /** 模組代號（設定鍵 {@code modules.importer.*}）。 */
    public static final String ID = "importer";

    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.feature.importer.messages");
    private final Function<String, String> env;
    private ModuleHost host;
    private ExecutorService background;

    /** ServiceLoader 用。 */
    public ImporterDesktopFeature() {
        this(System::getenv);
    }

    /** @param env 讀環境變數（測試替換） */
    ImporterDesktopFeature(Function<String, String> env) {
        this.env = env;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return text.getString("module.name");
    }

    @Override
    public void attach(ModuleHost value) {
        // 只存起來：attach 不做 I/O（DesktopFeature#attach）
        host = Objects.requireNonNull(value);
    }

    @Override
    public List<MenuPage> menuPages() {
        return List.of(new MenuPage(text.getString("page.title"), this::createPage));
    }

    /** 外殼每次開頁呼叫一次（JavaFX 執行緒，在外殼的 ModuleGuard 裡）。 */
    private Node createPage() {
        if (host == null) {
            throw new IllegalStateException("模組還沒 attach");
        }
        // 頁面內的動作與回呼另外包一層：出錯只記錄，頁面顯示「沒有匯入」的錯誤（Architecture §1.1）
        ModuleGuard guard = new ModuleGuard(ModuleFailureListener.NONE);
        ImportWizardModel model = new ImportWizardModel(importHost(host), guard, background(), Platform::runLater);
        return new ImportWizardPage(model, text, picker(Boolean.getBoolean("svault.dev")), guard);
    }

    /**
     * @param host 外殼給本模組的服務
     * @return core 的匯入精靈用到的三個服務（T-117），直接轉給 {@code host}
     */
    static ImportHost importHost(ModuleHost host) {
        return new ImportHost() {
            @Override
            public Optional<UnlockedEntries> unlockedEntries() {
                return host.unlockedEntries();
            }

            @Override
            public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                return host.whenEntriesLoaded(executor, callback);
            }

            @Override
            public void addEntries(EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback) {
                host.addEntries(batch, executor, callback);
            }
        };
    }

    /**
     * @param devMode 開發版（{@code svault.dev=true}）
     * @return 選檔方式：開發版設了 {@link CsvPicker#DEV_ENV} 時用固定路徑（畫面腳本），其他一律是系統對話框
     */
    CsvPicker picker(boolean devMode) {
        String fixed = devMode ? env.apply(CsvPicker.DEV_ENV) : null;
        if (fixed != null && !fixed.trim().isEmpty()) {
            return CsvPicker.fixed(Path.of(fixed.trim()));
        }
        return CsvPicker.system(text);
    }

    private synchronized ExecutorService background() {
        if (background == null) {
            background = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "svault-importer");
                // 不擋程式結束；讀檔只在記憶體，中斷不會留下任何東西
                t.setDaemon(true);
                return t;
            });
        }
        return background;
    }
}
