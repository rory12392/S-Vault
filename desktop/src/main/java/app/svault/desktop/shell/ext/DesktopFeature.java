package app.svault.desktop.shell.ext;

import app.svault.core.module.FeatureModule;
import java.util.List;

/**
 * desktop 的功能模組。在 {@code META-INF/services/app.svault.desktop.shell.ext.DesktopFeature} 註冊。
 *
 * <p>每個擴充點方法都由外殼包在 {@code ModuleGuard} 裡呼叫；預設不提供任何擴充。
 */
public interface DesktopFeature extends FeatureModule {

    /**
     * 外殼啟動時對每個啟用中的模組呼叫一次（JavaFX 執行緒，在任何擴充點之前；包在 {@code ModuleGuard} 裡，
     * 丟例外時這個模組整個停用）。模組把 {@code host} 存起來，之後在頁面、按鈕、設定段落裡讀寫自己的設定。
     * 不得做 I/O 或耗時工作。
     *
     * @param host 這個模組專用的外殼服務
     */
    default void attach(ModuleHost host) {
    }

    /** @return 選單中的獨立頁面 */
    default List<MenuPage> menuPages() {
        return List.of();
    }

    /** @return 編輯畫面密碼欄旁的按鈕 */
    default List<PasswordFieldAction> passwordFieldActions() {
        return List.of();
    }

    /** @return 設定畫面中的模組段落 */
    default List<SettingsSection> settingsSections() {
        return List.of();
    }
}
