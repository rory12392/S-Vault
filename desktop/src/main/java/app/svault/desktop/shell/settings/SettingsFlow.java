package app.svault.desktop.shell.settings;

import app.svault.core.module.ModuleRegistry;
import app.svault.core.storage.LiveSettings;
import app.svault.desktop.shell.AppShell;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.ui.Shortcut;
import app.svault.desktop.ui.ShortcutRegistry;
import app.svault.desktop.ui.Toast;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.scene.control.MenuItem;

/**
 * 設定與關於的入口（T-23，US-12）：工具列選單的「設定」（{@code Ctrl+,}）與「關於」，設定頁開在右側兩欄
 * （{@link AppShell#showPage}）。只在解鎖後出現（工具列只在三欄畫面）。
 */
public final class SettingsFlow {

    /**
     * 設定畫面用到的外部資源。
     *
     * @param settings 執行中的設定（主題的切換由建立者以 {@link LiveSettings.Listener} 跟隨）
     * @param dataDir  本機資料夾（關於頁顯示）；沒有時 {@code null}
     * @param version  {@link AboutInfo#VERSION_PROPERTY} 的值；開發執行時 {@code null}
     * @param copyText 複製一般文字到剪貼簿（不清除）
     */
    public record Env(LiveSettings settings, Path dataDir, String version, Consumer<String> copyText) {

        /** @return 正式程式讀的版本系統屬性 */
        public static String runtimeVersion() {
            return System.getProperty(AboutInfo.VERSION_PROPERTY);
        }
    }

    private final AppShell shell;
    private final Env env;
    private final ModuleRegistry<DesktopFeature> modules;
    private final Runnable openBackups;
    private final ResourceBundle text = ResourceBundle.getBundle("app.svault.desktop.shell.settings.messages");
    private final SettingsSaver saver;

    /**
     * @param shell       主視窗外框
     * @param env         設定與關於用到的資源
     * @param modules     desktop 功能模組（設定段落）
     * @param background  寫 {@code settings.json} 的背景執行緒
     * @param openBackups 開備份還原（同工具列選單）
     */
    public SettingsFlow(AppShell shell, Env env, ModuleRegistry<DesktopFeature> modules, Executor background,
            Runnable openBackups) {
        this.shell = Objects.requireNonNull(shell, "shell");
        this.env = Objects.requireNonNull(env, "env");
        this.modules = Objects.requireNonNull(modules, "modules");
        this.openBackups = Objects.requireNonNull(openBackups, "openBackups");
        this.saver = new SettingsSaver(env.settings(), background, Platform::runLater,
                () -> shell.toasts().show(Toast.Kind.ERROR, text.getString("settings.saveFailed")));
    }

    /** @return 工具列選單的「設定」「關於」 */
    public List<MenuItem> menuItems() {
        MenuItem settings = new MenuItem(text.getString("settings.menu"));
        settings.setId("menu-settings");
        settings.setAccelerator(Shortcut.SETTINGS.combination());
        settings.setOnAction(e -> open());
        MenuItem about = new MenuItem(text.getString("about.menu"));
        about.setId("menu-about");
        about.setOnAction(e -> openAbout());
        return List.of(settings, about);
    }

    /** 解鎖後：綁定 {@code Ctrl+,}。 */
    public void attach() {
        if (shell.getScene() != null) {
            ShortcutRegistry registry = ShortcutRegistry.of(shell.getScene());
            registry.unbind(Shortcut.SETTINGS);
            registry.bind(Shortcut.SETTINGS, this::open);
        }
    }

    /** 上鎖時：解除 {@code Ctrl+,}（設定頁隨三欄一起被換掉）。 */
    public void detach() {
        if (shell.getScene() != null) {
            ShortcutRegistry.of(shell.getScene()).unbind(Shortcut.SETTINGS);
        }
    }

    /** 開設定頁（已經開著時以目前的設定重新建立），焦點在第一個控制項。 */
    public void open() {
        SettingsPage.Actions actions = new SettingsPage.Actions() {
            @Override
            public void close() {
                shell.closePage();
            }

            @Override
            public void openBackups() {
                openBackups.run();
            }

            @Override
            public void openAbout() {
                SettingsFlow.this.openAbout();
            }
        };
        // 「功能模組」區段（T-38）：停用中的模組也列出，開關顯示設定裡的值（可能還沒重新啟動）
        SettingsPage page = new SettingsPage(text, saver, ModuleSections.build(modules),
                ModuleToggleRows.build(text, saver, modules.toggles(saver.current())), actions);
        shell.showPage(page);
        page.focusDefault();
        Platform.runLater(page::focusDefault);
    }

    /** 開關於對話框。 */
    public void openAbout() {
        new AboutDialog(shell, text, env.version(), env.dataDir(), path -> {
            env.copyText().accept(path);
            shell.toasts().show(Toast.Kind.INFO, text.getString("about.copied"));
        }).showAndWait();
    }
}
