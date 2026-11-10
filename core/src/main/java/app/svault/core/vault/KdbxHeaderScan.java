package app.svault.core.vault;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import org.linguafranca.pwdb.kdbx.KdbxSerializer;
import org.linguafranca.pwdb.security.VariantDictionary;

/**
 * 在解密前只讀未加密的外層標頭，判斷「不是 KDBX」與「不支援的加密設定」（AC-01-7）。
 *
 * <p>KeePassJava2 遇到 Argon2id 時拋的是泛用的 {@code IllegalArgumentException("Unknown Cipher UUID")}，
 * 和外層加密法不明的訊息相同，無法判斷是哪一個設定，所以先自己看標頭的 UUID。
 * 這裡只解析 KDBX 的欄位格式（類型、長度、值），不做任何加解密。
 */
final class KdbxHeaderScan {

    private static final int SIG1 = 0x9AA2D903;
    private static final int SIG2 = 0xB54BFB67;
    private static final byte END = 0;
    private static final byte CIPHER_ID = 2;
    private static final byte KDF_PARAMETERS = 11;

    static final UUID CIPHER_AES = UUID.fromString("31c1f2e6-bf71-4350-be58-05216afc5aff");
    static final UUID CIPHER_CHACHA20 = UUID.fromString("d6038a2b-8b6f-4cb5-a524-339a31dbb59a");
    private static final UUID CIPHER_TWOFISH = UUID.fromString("ad68f29f-576f-4bb9-a36a-d47af965346c");
    static final UUID KDF_ARGON2D = UUID.fromString("ef636ddf-8c29-444b-91f7-a9a403e30a0c");
    static final UUID KDF_AES = UUID.fromString("c9d9f39a-628a-4460-bf74-0d08c18a4fea");
    private static final UUID KDF_ARGON2ID = UUID.fromString("9e298b19-56db-4773-b23d-fc3ec6f0a1e6");

    /*
     * KDF 參數上限（2026-11-03 資安總檢 L-1）：KDF 在 HMAC 驗證之前執行，不知道主密碼的人也能改標頭，把參數改大就能讓
     * 解鎖卡死或記憶體耗盡。上限取 KeePassXC 預設（64 MiB、約 1 秒）的數倍，手機仍跑得動；S-Vault 自己建立的是
     * 64 MiB、2 次、平行度 4（AC-01-8）。
     */
    static final long MAX_ARGON2_MEMORY = 256L * 1024 * 1024;
    static final long MAX_ARGON2_ITERATIONS = 100;
    static final long MAX_ARGON2_PARALLELISM = 64;
    static final long MAX_AES_ROUNDS = 100_000_000L;

    private KdbxHeaderScan() {
    }

    /**
     * @param data 整個檔案的位元組（不修改）
     * @return 金鑰衍生的成本是預設的幾倍（{@link KdfCost}）
     * @throws CorruptVaultException   不是 KDBX 或標頭不完整
     * @throws UnsupportedKdfException KDBX 版本、外層加密法或 KDF 不支援
     */
    static double check(byte[] data) throws CorruptVaultException, UnsupportedKdfException {
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        UUID cipher = null;
        UUID kdf = null;
        VariantDictionary params = null;
        try {
            if (buf.getInt() != SIG1 || buf.getInt() != SIG2) {
                throw new CorruptVaultException("不是 KDBX 檔案");
            }
            int major = buf.getInt() >>> 16;
            if (major != 4) {
                // KDBX 3.1 的標頭格式不同（長度欄是 2 位元組），也不在 S-Vault 的範圍（PRD §3：標準 KDBX 4）
                throw new UnsupportedKdfException("KDBX " + (major == 3 ? "3.1" : String.valueOf(major)));
            }
            byte type;
            do {
                type = buf.get();
                int length = buf.getInt();
                if (length < 0 || length > buf.remaining()) {
                    throw new CorruptVaultException("標頭欄位長度不正確");
                }
                byte[] value = new byte[length];
                buf.get(value);
                if (type == CIPHER_ID && length == 16) {
                    ByteBuffer b = ByteBuffer.wrap(value);
                    cipher = new UUID(b.getLong(), b.getLong());
                } else if (type == KDF_PARAMETERS) {
                    params = KdbxSerializer.readVariantDictionary(value);
                    kdf = params.mustGet("$UUID").asUuid();
                }
            } while (type != END);
            if (kdf != null && tooCostly(kdf, params)) {
                throw new UnsupportedKdfException(UnsupportedKdfException.TOO_COSTLY);
            }
        } catch (RuntimeException e) {
            // 截斷或亂掉的標頭：BufferUnderflowException、函式庫解析 VariantDictionary 時的各種執行期例外
            throw new CorruptVaultException("標頭不完整：" + e.getClass().getSimpleName());
        }
        if (cipher == null || kdf == null) {
            throw new CorruptVaultException("標頭缺少加密設定");
        }
        if (!cipher.equals(CIPHER_AES) && !cipher.equals(CIPHER_CHACHA20)) {
            throw new UnsupportedKdfException(cipher.equals(CIPHER_TWOFISH) ? "Twofish" : "未知的加密法");
        }
        if (kdf.equals(KDF_ARGON2ID)) {
            throw new UnsupportedKdfException("Argon2id");
        }
        if (!kdf.equals(KDF_ARGON2D) && !kdf.equals(KDF_AES)) {
            throw new UnsupportedKdfException("未知的金鑰衍生函式");
        }
        // 走到這裡的參數都在 tooCostly 的上限內（不會是負的超大值）
        if (kdf.equals(KDF_AES)) {
            return KdfCost.aes(value(params, "R"));
        }
        return KdfCost.argon2(value(params, "M"), value(params, "I"));
    }

    private static long value(VariantDictionary params, String key) {
        VariantDictionary.Entry e = params.get(key);
        if (e == null) {
            return 0;
        }
        return e.asByteArray().length == 4 ? Integer.toUnsignedLong(e.asInteger()) : e.asLong();
    }

    /** 參數是不帶正負號的整數：以 {@code Long.compareUnsigned} 比較，超大值（負的 long）也算超過。 */
    private static boolean tooCostly(UUID kdf, VariantDictionary params) {
        if (kdf.equals(KDF_ARGON2D) || kdf.equals(KDF_ARGON2ID)) {
            return over(params, "M", MAX_ARGON2_MEMORY) || over(params, "I", MAX_ARGON2_ITERATIONS)
                    || over(params, "P", MAX_ARGON2_PARALLELISM);
        }
        return kdf.equals(KDF_AES) && over(params, "R", MAX_AES_ROUNDS);
    }

    private static boolean over(VariantDictionary params, String key, long max) {
        if (params.get(key) == null) {
            return false;
        }
        // P 是 UInt32（4 位元組），其他是 UInt64
        return Long.compareUnsigned(value(params, key), max) > 0;
    }
}
