package app.svault.core.module.fake;

import app.svault.core.module.FeatureModule;
import java.util.List;

/**
 * 測試用的「平台模組介面」，模擬 DesktopFeature／AndroidFeature 的擴充點。
 * 假模組在 {@code src/test/resources/META-INF/services/} 註冊。
 */
public interface TestFeature extends FeatureModule {

    /** @return 模組提供的頁面名稱 */
    default List<String> pages() {
        return List.of();
    }
}
