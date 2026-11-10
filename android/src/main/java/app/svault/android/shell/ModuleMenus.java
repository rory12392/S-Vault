package app.svault.android.shell;

import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.MenuPage;
import app.svault.core.module.Contribution;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * 首頁溢位選單裡功能模組的頁面（T-30；Architecture §1.1 的 {@code MenuPage}、DESIGN §4）：每個啟用中的模組的
 * {@link MenuPage} 一個項目，排在「新增群組」之後、「備份還原」之前。選了就以 {@link FeatureHostActivity} 載入頁面的
 * Fragment（在錯誤邊界裡，建立失敗時是錯誤狀態）。停用的模組沒有項目。不依賴畫面元件，可以 JVM 測試。
 */
public final class ModuleMenus {

    /**
     * 選單中的一個模組頁面。
     *
     * @param moduleId  提供的模組
     * @param title     選單與 Top app bar 的文字（標題資源是 0 或讀不到時用模組名稱）
     * @param pageClass 頁面的 Fragment 類別名稱；模組沒給時 {@code null}（開出來是錯誤狀態）
     */
    public record Item(String moduleId, String title, String pageClass) { }

    private ModuleMenus() {
    }

    /**
     * @param registry Android 功能模組（停用的不列）
     * @param strings  讀字串資源（{@code Context::getText}）；資源不存在時丟例外
     * @return 依模組順序的項目
     */
    public static List<Item> items(ModuleRegistry<AndroidFeature> registry, IntFunction<CharSequence> strings) {
        ModuleGuard guard = registry.guard();
        List<Item> out = new ArrayList<>();
        for (Contribution<MenuPage> c : registry.collect("menuPages", AndroidFeature::menuPages)) {
            String id = c.moduleId();
            MenuPage page = c.value();
            String title = page.titleRes() == 0 ? null
                    : guard.call(id, "menuTitle", () -> strings.apply(page.titleRes())).map(CharSequence::toString)
                            .filter(s -> !s.trim().isEmpty()).orElse(null);
            if (title == null) {
                title = displayName(registry, id);
            }
            out.add(new Item(id, title, page.fragmentClass() == null ? null : page.fragmentClass().getName()));
        }
        return List.copyOf(out);
    }

    private static String displayName(ModuleRegistry<AndroidFeature> registry, String id) {
        ModuleGuard guard = registry.guard();
        for (AndroidFeature module : registry.all()) {
            if (guard.call(module.getClass().getName(), "id", module::id).orElse("").equals(id)) {
                return guard.call(id, "displayName", module::displayName).orElse(id);
            }
        }
        return id;
    }
}
