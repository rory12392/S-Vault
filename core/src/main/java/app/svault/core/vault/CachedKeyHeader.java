package app.svault.core.vault;

import org.linguafranca.pwdb.kdbx.KdbxHeader;
import org.linguafranca.pwdb.kdbx.KdbxSerializer;

/**
 * KDBX 4 標頭，KDF 結果交給 {@link DerivedKeyCache}：函式庫在一次開檔／存檔中呼叫
 * {@link #getTransformedKeyDigest} 3 次，這裡讓它只真的算一次，金鑰沒變時一次都不算。
 * KDF 本身仍由函式庫（Bouncy Castle 的 Argon2）計算，不自己實作。
 */
final class CachedKeyHeader extends KdbxHeader {

    private final DerivedKeyCache cache;
    private int derivations;

    CachedKeyHeader(DerivedKeyCache cache) {
        super(4);
        this.cache = cache;
    }

    @Override
    public byte[] getTransformedKeyDigest(byte[] digest) {
        if (getKdfParameters() == null) {
            // KDBX 3 才沒有 KDF 參數；VaultCodec 開檔前已擋下，這裡只是防禦
            derivations++;
            return super.getTransformedKeyDigest(digest);
        }
        byte[] params = KdbxSerializer.serializeVariantDictionary(getKdfParameters());
        byte[] cached = cache.lookup(digest, params);
        if (cached != null) {
            return cached;
        }
        derivations++;
        byte[] derived = super.getTransformedKeyDigest(digest);
        cache.store(digest, params, derived);
        return derived;
    }

    /** @return 這個標頭實際執行 KDF 的次數（測試用） */
    int derivations() {
        return derivations;
    }
}
