package app.svault.android.feature.biometric;

import androidx.annotation.Nullable;
import java.util.Optional;
import java.util.Set;

/**
 * 設定段落要顯示什麼（T-72；AC-11-1、AC-11-4）：依目前解鎖的保管庫顯示「啟用」或「停用」，別的保管庫也有資料時另外
 * 提供整台裝置的「停用所有保管庫」。和 Android 畫面無關，在 JVM 上測。
 *
 * @param vaultId        目前解鎖的保管庫 ID（小寫）；上鎖或還沒接上時為 {@code null}
 * @param currentEnabled 目前的保管庫已啟用（有密文）
 * @param othersEnabled  別的保管庫已啟用的數量
 * @param othersData     別的保管庫有密文或金鑰
 * @param unavailable    裝置不能用 Class 3 生物辨識的原因；可用時為 {@code null}
 */
record BioSettingsState(@Nullable String vaultId, boolean currentEnabled, int othersEnabled, boolean othersData,
        @Nullable BioReason unavailable) {

    /**
     * @param vault       金鑰與密文
     * @param vaultId     {@code ModuleHost.unlockedVaultId()}（每次重新取，不存）
     * @param unavailable {@link BioPrompt#strongUnavailableReason} 的結果
     * @return 目前的狀態
     * @throws BioException 金鑰庫或私有偏好讀取錯誤
     */
    static BioSettingsState read(BioVault vault, Optional<String> vaultId, @Nullable BioReason unavailable)
            throws BioException {
        String id = vaultId.map(BioVault::normalize).orElse(null);
        Set<String> enabled = vault.enabledVaultIds();
        Set<String> data = vault.dataVaultIds();
        boolean current = id != null && enabled.remove(id);
        if (id != null) {
            data.remove(id);
        }
        return new BioSettingsState(id, current, enabled.size(), !data.isEmpty(), unavailable);
    }

    /** @return 顯示「啟用生物辨識解鎖」：有解鎖的保管庫、還沒啟用、裝置支援 */
    boolean canEnable() {
        return vaultId != null && !currentEnabled && unavailable == null;
    }

    /** @return 顯示「停用」（只停目前的保管庫）；裝置不支援時照樣可以停用 */
    boolean canDisableCurrent() {
        return vaultId != null && currentEnabled;
    }

    /** @return 顯示「停用所有保管庫」：別的保管庫也有資料 */
    boolean canDisableAll() {
        return othersData;
    }
}
