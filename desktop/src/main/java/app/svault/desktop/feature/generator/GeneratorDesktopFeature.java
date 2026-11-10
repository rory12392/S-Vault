package app.svault.desktop.feature.generator;

import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.feature.generator.GeneratorSettings;
import app.svault.core.feature.generator.PasswordGenerator;
import app.svault.core.module.ModuleClipboard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.security.PasswordStrength;
import app.svault.core.security.StrengthEstimator;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.ModuleHost;
import app.svault.desktop.shell.ext.PasswordFieldAction;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Node;

/**
 * 密碼產生器（GEN，US-04）的 desktop 模組類別（T-41）：註冊編輯畫面密碼欄旁的「產生」（{@link PasswordFieldAction}，
 * 彈出面板、「使用這個密碼」回填欄位）與選單的「密碼產生器」（{@link MenuPage}，獨立頁；「複製」走 host 的剪貼簿，
 * T-43）。兩者共用 {@link GeneratorModel}，選項記在 {@code modules.generator.*}（核心的 {@link GeneratorSettings}）。
 *
 * <p>強度用核心的 {@link StrengthEstimator}：整個模組共用一個，第一次需要時在背景執行緒建立（要載入字典）。
 * 產生的密碼不記錄日誌。
 */
public final class GeneratorDesktopFeature implements DesktopFeature {

    /** 模組代號（設定鍵 {@code modules.generator.*}）。 */
    public static final String ID = "generator";

    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.feature.generator.messages");
    private final PasswordGenerator generator = new PasswordGenerator();
    private ModuleHost host;
    private ExecutorService background;
    private volatile StrengthEstimator estimator;

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
    public List<PasswordFieldAction> passwordFieldActions() {
        return List.of(new PasswordFieldAction(text.getString("action.generate"),
                () -> GeneratorDialog.show(newModel(), text).orElse(null)));
    }

    @Override
    public List<MenuPage> menuPages() {
        return List.of(new MenuPage(text.getString("page.title"), this::createPage));
    }

    private Node createPage() {
        return GeneratorPage.create(newModel(), text, copier());
    }

    /**
     * 獨立頁「複製」用的剪貼簿：外殼的 {@code ClipboardGuard}（T-34），清除秒數與上鎖時清除都由外殼決定，模組不另外處理。
     *
     * @return host 沒有剪貼簿時為空（不顯示「複製」）
     */
    Optional<ModuleClipboard> copier() {
        return host().clipboard();
    }

    private GeneratorModel newModel() {
        ModulePreferences prefs = preferences();
        return new GeneratorModel(GeneratorSettings.load(prefs), generator, this::estimate, background(),
                Platform::runLater, s -> s.save(prefs));
    }

    private ModulePreferences preferences() {
        return host().preferences();
    }

    private ModuleHost host() {
        if (host == null) {
            throw new IllegalStateException("模組還沒 attach");
        }
        return host;
    }

    private synchronized ExecutorService background() {
        if (background == null) {
            background = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "svault-generator-strength");
                // 不擋程式結束
                t.setDaemon(true);
                return t;
            });
        }
        return background;
    }

    // 只在背景執行緒呼叫
    private PasswordStrength estimate(CharSequence password) {
        StrengthEstimator e = estimator;
        if (e == null) {
            synchronized (this) {
                if (estimator == null) {
                    estimator = new StrengthEstimator();
                }
                e = estimator;
            }
        }
        return e.estimate(password);
    }
}
