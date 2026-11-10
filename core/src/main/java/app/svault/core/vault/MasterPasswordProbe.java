package app.svault.core.vault;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Objects;
import org.linguafranca.pwdb.kdbx.KdbxCreds;

/**
 * 確認輸入的是不是開啟中保管庫的主密碼（T-35，BIO 啟用前再輸入一次主密碼，D-165）。
 *
 * <p>做法：由函式庫（{@link KdbxCreds}）把輸入算成 KDBX 的複合金鑰（SHA-256(SHA-256(主密碼))），和開啟中保管庫的
 * {@link KeyMaterial} 以 {@link MessageDigest#isEqual}（定時）比對。複合金鑰就是 KDF 的輸入，相同就代表主密碼相同，所以
 * 不必再跑一次 Argon2（手機約 1.9 秒、64 MiB）；S-Vault 自己沒有算任何雜湊或金鑰。
 *
 * <p>{@link #of} 當場算好，不保留傳入的陣列：呼叫端在 {@code of} 回傳後就可以清零。持有的複合金鑰等同主密碼，用完一定要
 * {@link #close()}（清零）；{@code toString()} 不輸出內容。
 */
public final class MasterPasswordProbe implements AutoCloseable {

    private byte[] candidate;

    private MasterPasswordProbe(byte[] candidate) {
        this.candidate = candidate;
    }

    /**
     * @param password 使用者輸入的主密碼（UTF-8）；不會被保留，呼叫端負責清零
     * @return 比對用的探針
     * @throws NullPointerException {@code password} 是 {@code null}
     */
    public static MasterPasswordProbe of(byte[] password) {
        Objects.requireNonNull(password, "password");
        // KdbxCreds 的 getKey() 回傳它內部的陣列本身，直接接手才能清零（同 KeyMaterial.fromPassword）
        return new MasterPasswordProbe(new KdbxCreds(password).getKey());
    }

    /**
     * 在保管庫的背景執行緒呼叫（{@link Vault} 不是執行緒安全的）。
     *
     * @param vault 開啟中的保管庫
     * @return 輸入的主密碼就是它的主密碼時為 {@code true}
     * @throws IllegalStateException 保管庫已上鎖，或本探針已關閉
     */
    public boolean matches(Vault vault) {
        if (candidate == null) {
            throw new IllegalStateException("探針已關閉");
        }
        return MessageDigest.isEqual(candidate, vault.key().getKey());
    }

    /** 清零複合金鑰；之後 {@link #matches} 會丟 {@link IllegalStateException}。重複呼叫沒有作用。 */
    @Override
    public void close() {
        if (candidate != null) {
            Arrays.fill(candidate, (byte) 0);
            candidate = null;
        }
    }

    @Override
    public String toString() {
        return "MasterPasswordProbe[***]";
    }
}
