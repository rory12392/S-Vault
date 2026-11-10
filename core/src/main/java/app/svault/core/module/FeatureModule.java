package app.svault.core.module;

/**
 * 功能模組的共同介面。各平台的模組類別（desktop 的 {@code DesktopFeature}、android 的
 * {@code AndroidFeature}）繼承它，並在該平台的 {@code META-INF/services/} 註冊，由
 * {@link ModuleRegistry} 以 ServiceLoader 探索。
 *
 * <p>實作類別必須有 public 無參數建構子，建構子不得做 I/O 或耗時工作：探索時會建立實例。
 */
public interface FeatureModule {

    /**
     * 模組代號，用在設定鍵 {@code modules.<id>.*}。
     *
     * @return 全小寫的代號，例如 {@code generator}；同一平台內不可重複
     */
    String id();

    /**
     * 顯示名稱。
     *
     * @return 給使用者看的模組名稱（繁體中文）
     */
    String displayName();

    /**
     * 設定檔沒有 {@code modules.<id>.enabled} 時是否啟用。
     *
     * @return 預設啟用時為 {@code true}
     */
    default boolean enabledByDefault() {
        return true;
    }
}
