package app.svault.desktop.shell;

import app.svault.core.module.Contribution;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.MenuPage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.MenuItem;

/**
 * 工具列選單裡功能模組的頁面（T-30；Architecture §1.1 的 {@code MenuPage}、DESIGN §4）：每個啟用中的模組的
 * {@link MenuPage} 一個選單項目，放在「備份還原」之前（產生器、健檢、匯入依模組的探索順序）。選了就在右側兩欄開
 * {@link ModulePage}；頁面工廠包在 {@link ModuleGuard} 裡，丟例外時頁面是錯誤狀態，其他功能照常。
 * 停用的模組沒有項目（{@link ModuleRegistry#collect} 本來就略過）。
 */
public final class ModuleMenu {

    /**
     * 選單中的一個模組頁面。
     *
     * @param moduleId 提供的模組
     * @param title    選單與頁首的文字（模組沒給標題時用模組名稱）
     * @param create   建立頁面內容（在錯誤邊界裡）；出錯時為空
     */
    record Page(String moduleId, String title, Supplier<Optional<Node>> create) { }

    private ModuleMenu() {
    }

    /**
     * @param registry desktop 功能模組（停用的不列）
     * @return 依模組順序的頁面
     */
    static List<Page> pages(ModuleRegistry<DesktopFeature> registry) {
        ModuleGuard guard = registry.guard();
        List<Page> out = new ArrayList<>();
        for (Contribution<MenuPage> c : registry.collect("menuPages", DesktopFeature::menuPages)) {
            String id = c.moduleId();
            MenuPage page = c.value();
            String title = page.title() == null || page.title().isBlank() ? displayName(registry, id) : page.title();
            Supplier<Node> factory = page.factory();
            out.add(new Page(id, title, () -> factory == null ? Optional.empty()
                    : guard.call(id, "createPage", factory)));
        }
        return List.copyOf(out);
    }

    /**
     * 選單項目（JavaFX 執行緒；每次解鎖重建）。項目 id 是 {@code menu-module-<模組代號>}，同一個模組的第二頁起加
     * {@code -2}、{@code -3}（畫面腳本的 {@code menu=} 用）。
     *
     * @param registry desktop 功能模組
     * @param shell    主視窗外框（頁面開在右側兩欄）
     * @return 選單項目
     */
    public static List<MenuItem> items(ModuleRegistry<DesktopFeature> registry, AppShell shell) {
        List<MenuItem> out = new ArrayList<>();
        List<String> seen = new ArrayList<>();
        for (Page page : pages(registry)) {
            seen.add(page.moduleId());
            long n = seen.stream().filter(page.moduleId()::equals).count();
            MenuItem item = new MenuItem(page.title());
            item.setId("menu-module-" + page.moduleId() + (n > 1 ? "-" + n : ""));
            item.setOnAction(e -> open(shell, page));
            out.add(item);
        }
        return List.copyOf(out);
    }

    /** 開頁面：每次重新建立內容（已經開著別的頁面時換掉它）。 */
    private static void open(AppShell shell, Page page) {
        ModulePage view = new ModulePage(page.title(), page.create().get(), shell::closePage);
        shell.showPage(view);
        view.focusDefault();
        // 同設定頁：showPage 之後的 pulse 可能把焦點交給別的元件，畫面顯示後再設一次
        Platform.runLater(view::focusDefault);
    }

    private static String displayName(ModuleRegistry<DesktopFeature> registry, String id) {
        for (DesktopFeature module : registry.all()) {
            if (registry.guard().call(module.getClass().getName(), "id", module::id).orElse("").equals(id)) {
                return registry.guard().call(id, "displayName", module::displayName).orElse(id);
            }
        }
        return id;
    }
}
