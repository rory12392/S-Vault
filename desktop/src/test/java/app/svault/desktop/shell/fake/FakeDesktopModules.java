package app.svault.desktop.shell.fake;

import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.ModuleHost;
import app.svault.desktop.shell.ext.PasswordFieldAction;
import app.svault.desktop.shell.ext.SettingsSection;
import java.util.List;
import javafx.scene.Group;

/**
 * desktop 的停用與故障注入測試用的假模組（在測試的 META-INF/services 註冊）。
 */
public final class FakeDesktopModules {

    private FakeDesktopModules() {
    }

    /** 每個擴充點都提供一項的正常模組。 */
    public static final class Alpha implements DesktopFeature {
        @Override
        public String id() {
            return "alpha";
        }

        @Override
        public String displayName() {
            return "Alpha";
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage("alpha-page", Group::new));
        }

        @Override
        public List<PasswordFieldAction> passwordFieldActions() {
            return List.of(new PasswordFieldAction("alpha-action", () -> new char[] {'a'}));
        }

        @Override
        public List<SettingsSection> settingsSections() {
            return List.of(new SettingsSection("alpha-settings", Group::new));
        }
    }

    /** 只提供頁面的正常模組。 */
    public static final class Beta implements DesktopFeature {
        @Override
        public String id() {
            return "beta";
        }

        @Override
        public String displayName() {
            return "Beta";
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage("beta-page", Group::new));
        }
    }

    /** 擴充點本身丟例外、頁面工廠也丟例外的模組。 */
    public static final class Faulty implements DesktopFeature {
        @Override
        public String id() {
            return "faulty";
        }

        @Override
        public String displayName() {
            return "Faulty";
        }

        @Override
        public List<MenuPage> menuPages() {
            return List.of(new MenuPage("faulty-page", () -> {
                throw new IllegalStateException("頁面建立失敗");
            }));
        }

        @Override
        public List<SettingsSection> settingsSections() {
            throw new IllegalStateException("設定段落失敗");
        }

        @Override
        public void attach(ModuleHost host) {
            throw new IllegalStateException("attach 失敗");
        }

        @Override
        public List<PasswordFieldAction> passwordFieldActions() {
            return List.of(new PasswordFieldAction("faulty-action", () -> {
                throw new IllegalStateException("產生失敗");
            }));
        }
    }

    /** 建構子就失敗的模組。 */
    public static final class BrokenConstructor implements DesktopFeature {
        /** 永遠失敗。 */
        public BrokenConstructor() {
            throw new IllegalStateException("建構失敗");
        }

        @Override
        public String id() {
            return "broken";
        }

        @Override
        public String displayName() {
            return "Broken";
        }
    }
}
