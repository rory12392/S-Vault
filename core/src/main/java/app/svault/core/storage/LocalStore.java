package app.svault.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * 本機資料夾（Architecture §2.2）。根目錄由平台提供預設值，可用 {@code SVAULT_DATA_DIR} 覆寫。
 *
 * <p>這裡只決定路徑；檔案內容由 {@link SettingsStore}、{@link RecentStore}、{@link BackupStore}、
 * {@link SaveCoordinator} 讀寫。
 */
public final class LocalStore {

    /** 覆寫根目錄的系統屬性與環境變數名稱。 */
    public static final String DATA_DIR_KEY = "SVAULT_DATA_DIR";

    private final Path root;

    private LocalStore(Path root) {
        this.root = root;
    }

    /**
     * 依系統屬性、環境變數、平台預設的順序決定根目錄，並建立資料夾。
     *
     * @param platformDefault 平台預設根目錄（PC：{@code %APPDATA%\S-Vault}；Android：{@code filesDir/svault}）
     * @return 本機資料夾
     * @throws IOException 無法建立資料夾時
     */
    public static LocalStore open(Path platformDefault) throws IOException {
        return open(platformDefault, System::getProperty, System::getenv);
    }

    /**
     * 同 {@link #open(Path)}，但系統屬性與環境變數由呼叫端提供（測試用）。
     *
     * @param platformDefault 平台預設根目錄
     * @param systemProperty  查詢系統屬性
     * @param environment     查詢環境變數
     * @return 本機資料夾
     * @throws IOException 無法建立資料夾時
     */
    public static LocalStore open(Path platformDefault, UnaryOperator<String> systemProperty,
            UnaryOperator<String> environment) throws IOException {
        Path root = resolveRoot(platformDefault, systemProperty, environment);
        Files.createDirectories(root);
        return new LocalStore(root);
    }

    /**
     * 決定根目錄，不建立資料夾。系統屬性優先於環境變數（測試可以覆寫環境），空白值視為沒有設定。
     *
     * @param platformDefault 平台預設根目錄
     * @param systemProperty  查詢系統屬性
     * @param environment     查詢環境變數
     * @return 絕對路徑
     */
    public static Path resolveRoot(Path platformDefault, UnaryOperator<String> systemProperty,
            UnaryOperator<String> environment) {
        Objects.requireNonNull(platformDefault, "platformDefault");
        String override = firstNonBlank(systemProperty.apply(DATA_DIR_KEY), environment.apply(DATA_DIR_KEY));
        // Paths.get 而不是 Path.of：後者在 Android API 29 不存在
        Path chosen = override != null ? Paths.get(override) : platformDefault;
        return chosen.toAbsolutePath().normalize();
    }

    private static String firstNonBlank(String a, String b) {
        // trim().isEmpty() 而不是 isBlank()：後者在 Android API 29 不存在
        if (a != null && !a.trim().isEmpty()) {
            return a.trim();
        }
        if (b != null && !b.trim().isEmpty()) {
            return b.trim();
        }
        return null;
    }

    /**
     * @return 根目錄（絕對路徑）
     */
    public Path root() {
        return root;
    }

    /**
     * @return 日誌資料夾 {@code logs/}（不保證已建立）
     */
    public Path logsDir() {
        return root.resolve("logs");
    }

    /** @return {@code recent.json} */
    public Path recentFile() {
        return root.resolve("recent.json");
    }

    /** @return {@code settings.json} */
    public Path settingsFile() {
        return root.resolve("settings.json");
    }

    /** @return 備份資料夾 {@code backups/}（不保證已建立） */
    public Path backupsDir() {
        return root.resolve("backups");
    }

    /**
     * 三方合併的基準 {@code base/<保管庫ID>_<位置代碼>.kdbx}。以保管庫 ID＋位置區分（T-27）：使用者複製出來的檔案保管庫 ID
     * 相同，只用 ID 時兩個檔案會共用基準，合併用錯基準。
     *
     * @param vaultId  保管庫 ID
     * @param location 位置字串（{@link VaultLocation#persistentId()}）
     * @return 檔案路徑（不保證存在）
     */
    public Path baseFile(UUID vaultId, String location) {
        return root.resolve("base").resolve(vaultId + "_" + locationKey(location) + ".kdbx");
    }

    /**
     * 尚未寫回遠端的本機版本 {@code pending/<保管庫ID>_<位置代碼>.kdbx}（同 {@link #baseFile}）。
     *
     * @param vaultId  保管庫 ID
     * @param location 位置字串（{@link VaultLocation#persistentId()}）
     * @return 檔案路徑（不保證存在）
     */
    public Path pendingFile(UUID vaultId, String location) {
        return root.resolve("pending").resolve(vaultId + "_" + locationKey(location) + ".kdbx");
    }

    /**
     * 衝突沒解決時被上鎖的選擇 {@code pending/<保管庫ID>_<位置代碼>.conflict.kdbx}（AC-08-5，T-18；同一主密碼加密）。
     * 本機的修改本身在 {@link #pendingFile}。
     *
     * @param vaultId  保管庫 ID
     * @param location 位置字串（{@link VaultLocation#persistentId()}）
     * @return 檔案路徑（不保證存在）
     */
    public Path conflictFile(UUID vaultId, String location) {
        return root.resolve("pending").resolve(vaultId + "_" + locationKey(location) + ".conflict.kdbx");
    }

    /**
     * @param vaultId 保管庫 ID
     * @return T-27 之前只以保管庫 ID 命名的基準 {@code base/<保管庫ID>.kdbx}（升級時搬到 {@link #baseFile}）
     */
    public Path legacyBaseFile(UUID vaultId) {
        return root.resolve("base").resolve(vaultId + ".kdbx");
    }

    /**
     * @param vaultId 保管庫 ID
     * @return T-27 之前的 {@code pending/<保管庫ID>.kdbx}（升級時搬到 {@link #pendingFile}）
     */
    public Path legacyPendingFile(UUID vaultId) {
        return root.resolve("pending").resolve(vaultId + ".kdbx");
    }

    /**
     * 位置字串的 SHA-256 前 16 個十六進位字元：檔名不含路徑或 Uri（可能有使用者名稱、資料夾名稱），長度固定，
     * 任何平台的檔名都能用。
     */
    static String locationKey(String location) {
        return Hashes.sha256(location.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
    }
}
