package app.svault.core.vault;

import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 最近一次 KDF 的結果：同一組（複合金鑰、KDF 參數含鹽）只算一次 Argon2。
 *
 * <p>KeePassJava2 每次開檔會呼叫 3 次 KDF（外層標頭 HMAC、資料區塊 HMAC、解密各一次），存檔也一樣，
 * 手機上每次約 1.9 秒。KDF 參數（含鹽）在存檔時沿用，所以存檔與讀回驗證
 * 都不需要重算；每次存檔仍會換新主種子與 IV（{@link VaultCodec#serialize}），最終的加密金鑰每次都不同。
 */
final class DerivedKeyCache {

    private byte[] compositeKey;
    private byte[] kdfParameters;
    private byte[] transformedKey;

    /**
     * @return 快取的衍生金鑰；不符合時為 {@code null}。回傳的是內部陣列本身（函式庫只讀不改），
     *         避免在記憶體留下無法清零的複本
     */
    byte[] lookup(byte[] key, byte[] params) {
        if (transformedKey != null && MessageDigest.isEqual(compositeKey, key)
                && Arrays.equals(kdfParameters, params)) {
            return transformedKey;
        }
        return null;
    }

    void store(byte[] key, byte[] params, byte[] derived) {
        wipe();
        compositeKey = key.clone();
        kdfParameters = params.clone();
        transformedKey = derived;
    }

    DerivedKeyCache copy() {
        DerivedKeyCache copy = new DerivedKeyCache();
        if (transformedKey != null) {
            copy.store(compositeKey, kdfParameters, transformedKey.clone());
        }
        return copy;
    }

    void wipe() {
        if (compositeKey != null) {
            Arrays.fill(compositeKey, (byte) 0);
        }
        if (transformedKey != null) {
            Arrays.fill(transformedKey, (byte) 0);
        }
        compositeKey = null;
        kdfParameters = null;
        transformedKey = null;
    }
}
