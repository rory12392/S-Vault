package app.svault.desktop.shell.fake;

import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import app.svault.desktop.shell.ext.ModuleHost;
import app.svault.desktop.shell.ext.PasswordFieldAction;
import app.svault.desktop.shell.ext.SettingsSection;
import java.util.List;

/**
 * 故障注入：包住一個真的模組，代號與名稱照舊，{@code attach} 與每個擴充點都丟例外（T-22：ModuleFaultTest 涵蓋真的模組，
 * 不管模組分支之後在擴充點放了什麼）。
 */
public final class ThrowingDesktopFeature implements DesktopFeature {

    private final DesktopFeature real;

    /** @param real 被包住的模組 */
    public ThrowingDesktopFeature(DesktopFeature real) {
        this.real = real;
    }

    @Override
    public String id() {
        return real.id();
    }

    @Override
    public String displayName() {
        return real.displayName();
    }

    @Override
    public void attach(ModuleHost host) {
        throw new IllegalStateException("注入：attach");
    }

    @Override
    public List<MenuPage> menuPages() {
        throw new IllegalStateException("注入：menuPages");
    }

    @Override
    public List<PasswordFieldAction> passwordFieldActions() {
        throw new IllegalStateException("注入：passwordFieldActions");
    }

    @Override
    public List<SettingsSection> settingsSections() {
        throw new IllegalStateException("注入：settingsSections");
    }
}
