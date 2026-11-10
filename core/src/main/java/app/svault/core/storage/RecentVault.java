package app.svault.core.storage;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code recent.json} 的一筆：最近開啟的保管庫（AC-06-3）。只記位置與非機密的狀態，不記主密碼。
 *
 * @param location    {@link VaultLocation#persistentId()}（PC 路徑／Android Uri 字串）
 * @param displayName 顯示名稱
 * @param vaultId     保管庫 ID（根群組 UUID）
 * @param lastOpened  最後開啟時間
 * @param remoteHash  上次載入或寫入遠端時的 SHA-256（小寫十六進位）；解鎖時比對它就知道遠端有沒有變（Architecture §5）。
 *                    不知道時為 {@code null}
 */
public record RecentVault(String location, String displayName, UUID vaultId, Instant lastOpened, String remoteHash) {

    /**
     * 檢查必要欄位。
     */
    public RecentVault {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(vaultId, "vaultId");
        Objects.requireNonNull(lastOpened, "lastOpened");
    }

    /**
     * @param hash 新的遠端雜湊
     * @return 只換掉遠端雜湊的複本
     */
    public RecentVault withRemoteHash(String hash) {
        return new RecentVault(location, displayName, vaultId, lastOpened, hash);
    }
}
