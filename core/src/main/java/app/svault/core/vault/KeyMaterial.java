package app.svault.core.vault;

import java.util.Arrays;
import org.linguafranca.pwdb.Credentials;
import org.linguafranca.pwdb.kdbx.KdbxCreds;

/**
 * 解鎖期間保留在記憶體的金鑰材料：主密碼的複合金鑰（KDBX 的 SHA-256(SHA-256(主密碼))）與
 * 已衍生的金鑰快取。兩者都等同主密碼，所以只存在這裡、上鎖時清零（CONVENTIONS §2、Architecture §6）。
 *
 * <p>不保留主密碼本身：呼叫端傳入的 {@code byte[]} 用完由呼叫端清零。
 */
final class KeyMaterial implements Credentials {

    private byte[] compositeKey;
    private final DerivedKeyCache cache;

    private KeyMaterial(byte[] compositeKey, DerivedKeyCache cache) {
        this.compositeKey = compositeKey;
        this.cache = cache;
    }

    /**
     * @param password 主密碼（UTF-8）；不會被保留
     */
    static KeyMaterial fromPassword(byte[] password) {
        // KdbxCreds 的 getKey() 回傳它內部的陣列本身，直接接手，才能在上鎖時清零
        return new KeyMaterial(new KdbxCreds(password).getKey(), new DerivedKeyCache());
    }

    /** @return 獨立的複本（讀回驗證用的 Vault 關閉時不會清掉來源的金鑰） */
    KeyMaterial copy() {
        return new KeyMaterial(key().clone(), cache.copy());
    }

    DerivedKeyCache cache() {
        return cache;
    }

    @Override
    public byte[] getKey() {
        return key();
    }

    private byte[] key() {
        if (compositeKey == null) {
            throw new IllegalStateException("保管庫已上鎖");
        }
        return compositeKey;
    }

    /** 清零複合金鑰與快取；之後任何使用都會拋 {@link IllegalStateException}。 */
    void wipe() {
        if (compositeKey != null) {
            Arrays.fill(compositeKey, (byte) 0);
            compositeKey = null;
        }
        cache.wipe();
    }
}
