package app.svault.desktop.feature.health;

import app.svault.core.feature.health.BreachChecker;
import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.feature.health.WeakReuseAnalyzer;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.security.StrengthEstimator;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.ModuleHost;
import app.svault.desktop.shell.ext.SettingsSection;
import app.svault.desktop.ui.SettingsRow;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Label;

/**
 * 健檢（HLT，US-13）的 desktop 模組類別：選單的「密碼健檢」頁（{@link HealthReportPage}）與設定的「密碼健檢」段落
 * （上次檢查外洩的時間）。保管庫只經 {@link ModuleHost#unlockedEntries()} 唯讀取用，跳到條目用
 * {@link ModuleHost#navigator()}。
 */
public final class HealthDesktopFeature implements DesktopFeature {

    /** 模組代號（設定鍵 {@code modules.health.*}）。 */
    public static final String ID = "health";

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.feature.health.messages");
    private final Clock clock;
    private ModuleHost host;
    /** 整個程式共用一個（建立時要載入 zxcvbn 字典）；第一次分析時在背景執行緒建立。 */
    private WeakReuseAnalyzer analyzer;

    /** ServiceLoader 用。 */
    public HealthDesktopFeature() {
        this(Clock.systemDefaultZone());
    }

    /** @param clock 記錄與顯示上次檢查外洩的時間（測試用固定時鐘） */
    HealthDesktopFeature(Clock clock) {
        this.clock = clock;
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
    public void attach(ModuleHost moduleHost) {
        host = moduleHost;
    }

    @Override
    public List<MenuPage> menuPages() {
        return List.of(new MenuPage(text.getString("page.title"), this::createPage));
    }

    @Override
    public List<SettingsSection> settingsSections() {
        return List.of(new SettingsSection(text.getString("settings.title"), this::createSettings));
    }

    /** 外殼每次開頁呼叫一次（JavaFX 執行緒，在外殼的 ModuleGuard 裡）。 */
    private Node createPage() {
        ExecutorService background = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "svault-health");
            t.setDaemon(true);
            return t;
        });
        HealthReportModel model = new HealthReportModel(host::unlockedEntries, this::analyzer, BreachChecker::new,
                background, Platform::runLater, host.preferences(), clock);
        ModuleGuard guard = new ModuleGuard(ModuleFailureListener.NONE);
        HealthReportPage page = new HealthReportPage(model, text, host.navigator(), guard, () -> {
            model.close();
            background.shutdown();
        });
        // 剛解鎖時條目畫面可能還在載入：載入好才算（已上鎖時模組頁本來就看不到）
        host.whenEntriesLoaded(Platform::runLater, () -> guard.run(ID, "analyze", model::analyze));
        return page;
    }

    private Node createSettings() {
        Label value = new Label(lastBreachCheck(host.preferences()));
        value.setId("hlt-settings-last-check");
        return new SettingsRow(text.getString("settings.lastCheck"), text.getString("settings.lastCheck.description"),
                value);
    }

    /**
     * @param prefs 本模組的設定
     * @return 上次檢查外洩的時間（裝置時區 {@code yyyy/MM/dd HH:mm}）；沒有紀錄時「尚未檢查」
     */
    String lastBreachCheck(ModulePreferences prefs) {
        Optional<Long> millis = prefs.number(HealthReportModel.KEY_LAST_BREACH_CHECK);
        if (millis.isEmpty() || millis.get() <= 0) {
            return text.getString("settings.lastCheck.never");
        }
        return TIME.format(Instant.ofEpochMilli(millis.get()).atZone(clock.getZone()));
    }

    private synchronized WeakReuseAnalyzer analyzer() {
        if (analyzer == null) {
            analyzer = new WeakReuseAnalyzer(new StrengthEstimator());
        }
        return analyzer;
    }
}
