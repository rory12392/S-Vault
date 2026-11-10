package app.svault.core.module;

import java.util.Optional;

/**
 * 模組啟用狀態的來源。正式程式是 {@code settings.json} 的 {@code modules.<id>.enabled}
 * （{@code app.svault.core.storage.Settings} 實作）。
 */
@FunctionalInterface
public interface ModuleSettings {

    /** 沒有任何設定：所有模組依 {@link FeatureModule#enabledByDefault()}。 */
    ModuleSettings DEFAULTS = id -> Optional.empty();

    /**
     * 查詢模組是否被明確啟用或停用。
     *
     * @param moduleId 模組代號
     * @return 有設定時回傳設定值；沒有設定時回傳空
     */
    Optional<Boolean> enabled(String moduleId);
}
