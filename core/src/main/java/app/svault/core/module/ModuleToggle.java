package app.svault.core.module;

/**
 * 設定頁「功能模組」區段的一列（T-38）：探索到的每個模組一列，停用中的也列出。
 * 模組只在 App 啟動時載入或略過，所以開關改了要重新啟動才生效（不在執行中動態載入或卸下）。
 *
 * @param id      模組代號（設定鍵 {@code modules.<id>.enabled}）
 * @param name    顯示名稱（{@link FeatureModule#displayName()}，在錯誤邊界內取得；失敗或空白時是代號）
 * @param running 這次啟動時是否啟用（執行中的狀態）
 * @param enabled 設定裡的值（沒有設定時依 {@link FeatureModule#enabledByDefault()}），也就是開關顯示的狀態
 */
public record ModuleToggle(String id, String name, boolean running, boolean enabled) {

    /** @return 設定和執行中的狀態不同：要重新啟動 S-Vault 才生效 */
    public boolean restartNeeded() {
        return running != enabled;
    }
}
