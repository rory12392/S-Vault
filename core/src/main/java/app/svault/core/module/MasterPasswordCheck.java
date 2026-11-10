package app.svault.core.module;

import java.util.Objects;
import java.util.Optional;

/**
 * {@code ModuleHost.verifyMasterPassword}（T-35，BIO 的啟用）的結果。只有 {@link Status#MATCH} 帶保管庫 ID：模組要用這個
 * ID（不是先前另外取的）命名金鑰，確認的和要啟用的一定是同一個保管庫。
 *
 * @param status  結果
 * @param vaultId 確認的保管庫 ID（根群組 UUID 的字串，{@link Status#MATCH} 時；其他為 {@code null}）
 */
public record MasterPasswordCheck(Status status, String vaultId) {

    /** 結果的種類。 */
    public enum Status {
        /** 輸入的就是開啟中保管庫的主密碼。 */
        MATCH,
        /** 主密碼不對。 */
        MISMATCH,
        /** 已上鎖（含按下上鎖之後、還沒解鎖過）：沒有比對。 */
        LOCKED,
        /** 這次解鎖已經錯太多次（{@code ModuleVaultIdentity.MAX_MISMATCHES}）：沒有比對，要上鎖再用主密碼解鎖。 */
        TOO_MANY_ATTEMPTS,
        /** 意外的錯誤：沒有比對。 */
        FAILED
    }

    /**
     * @param vaultId 確認的保管庫 ID
     * @return 相符的結果
     * @throws NullPointerException {@code vaultId} 是 {@code null}
     */
    public static MasterPasswordCheck matched(String vaultId) {
        return new MasterPasswordCheck(Status.MATCH, Objects.requireNonNull(vaultId, "vaultId"));
    }

    /**
     * @param status 不是 {@link Status#MATCH} 的結果
     * @return 不帶保管庫 ID 的結果
     * @throws IllegalArgumentException {@code status} 是 {@link Status#MATCH}
     */
    public static MasterPasswordCheck rejected(Status status) {
        if (status == Status.MATCH) {
            throw new IllegalArgumentException("MATCH 要用 matched(…)");
        }
        return new MasterPasswordCheck(status, null);
    }

    /** @return 主密碼相符 */
    public boolean matched() {
        return status == Status.MATCH;
    }

    /** @return 相符時是確認的保管庫 ID，其他為空 */
    public Optional<String> confirmedVaultId() {
        return Optional.ofNullable(vaultId);
    }
}
