package app.svault.core.vault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.UUID;
import org.linguafranca.pwdb.kdbx.KdbxSerializer;
import org.linguafranca.pwdb.kdbx.KdbxStreamFormat;
import org.linguafranca.pwdb.PropertyValue;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonSerializableDatabase;
import org.linguafranca.pwdb.security.Encryption;
import org.linguafranca.pwdb.security.VariantDictionary;

/**
 * KDBX 4 的開啟、建立與序列化（Architecture §6）。全專案唯一使用 KeePassJava2 的套件是 {@code core.vault}，
 * 對外只有這個類別與 {@link Vault}；加解密與 KDF 都由函式庫（Bouncy Castle）計算，這裡不實作任何演算法。
 *
 * <p>這個類別不讀寫檔案：輸入與輸出都是位元組，寫入遠端一律經過 {@code SaveCoordinator}（T-05）。
 * 方法是同步的，KDF 很慢（手機約 1.9 秒），呼叫端要放到背景執行緒（CONVENTIONS §2）。
 */
public final class VaultCodec {

    /** 外層 AES-256-CBC 的 IV 長度。 */
    private static final int AES_IV_BYTES = 16;
    /** 外層 ChaCha20 的 nonce 長度。 */
    private static final int CHACHA20_NONCE_BYTES = 12;
    /** 主種子長度（KDBX 4 規格）。 */
    private static final int MASTER_SEED_BYTES = 32;
    /** 內層串流金鑰長度：ChaCha20 用 64 位元組（SHA-512 後分成金鑰與 nonce），Salsa20 用 32。 */
    private static final int CHACHA20_INNER_KEY_BYTES = 64;
    private static final int SALSA20_INNER_KEY_BYTES = 32;
    /** 函式庫判斷主密碼錯誤時的訊息（外層標頭 HMAC 不符；CompatibilityTest／VaultCodecTest 釘住這個行為）。 */
    private static final String WRONG_KEY_MESSAGE = "Header HMAC does not match";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final KdfParams createParams;

    /** 建立保管庫時使用 AC-01-8 的正式參數（{@link KdfParams#DEFAULT}）。 */
    public VaultCodec() {
        this(KdfParams.DEFAULT);
    }

    /**
     * @param createParams 建立保管庫時的 Argon2d 參數；只有測試會傳入 {@link KdfParams#DEFAULT} 以外的值
     */
    public VaultCodec(KdfParams createParams) {
        this.createParams = createParams;
    }

    /**
     * 建立新的空保管庫（只在記憶體中，要 {@link #serialize} 後由呼叫端寫出）。
     * 加密設定：KDBX 4、AES-256、內層 ChaCha20、Argon2d 依建構時的參數（AC-01-1、AC-01-8）。
     *
     * @param name     保管庫名稱（也是根群組名稱）
     * @param password 主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @return 開啟中的保管庫，根群組 UUID 是新產生的
     */
    public Vault create(String name, byte[] password) {
        JacksonDatabase db;
        try {
            db = new JacksonDatabase();
        } catch (IOException e) {
            // 讀的是函式庫 jar 內的空資料庫範本，失敗表示執行環境有問題
            throw new IllegalStateException("無法建立空的保管庫：" + e.getClass().getSimpleName());
        }
        // 函式庫的範本裡根群組 UUID 是空的；保管庫 ID＝根群組 UUID，每個保管庫都要不同
        KdbxModelAccess.setGroupUuid(db.getRootGroup(), UUID.randomUUID());
        db.getRootGroup().setName(name);
        db.setName(name);
        // 範本的描述是「New Database created by KeePassJava2」，KeePassXC 的資料庫設定會顯示出來；留空
        db.setDescription("");

        KeyMaterial key = KeyMaterial.fromPassword(password);
        CachedKeyHeader header = new CachedKeyHeader(key.cache());
        // KdbxHeader(4) 預設就是 AES-256＋Argon2d＋內層 ChaCha20；參數依 AC-01-8 改寫（預設平行度是 2）
        VariantDictionary kdf = header.getKdfParameters();
        kdf.putULong("M", createParams.memoryBytes());
        kdf.putULong("I", createParams.iterations());
        kdf.putUInt("P", createParams.parallelism());
        return new Vault(db, header, key);
    }

    /**
     * 建立一個和 {@code keySource} 同一把金鑰、同一組 KDF 參數（含鹽）的新空保管庫：App 私有資料夾裡、要用同一主密碼加密
     * 的附屬檔案用（AC-08-5 的衝突暫存，T-18）。鹽相同，所以序列化與之後以 {@link #openWithKeyOf} 讀回都不重算 KDF；
     * 加密法用 S-Vault 的預設（AES-256、內層 ChaCha20）。
     *
     * @param name      保管庫名稱
     * @param keySource 提供金鑰的保管庫（必須還沒上鎖）；新保管庫拿的是複本，兩者各自上鎖
     * @return 開啟中的空保管庫，根群組 UUID 是新產生的
     * @throws IllegalStateException {@code keySource} 已上鎖
     */
    public Vault createWithKeyOf(String name, Vault keySource) {
        KeyMaterial key = keySource.key().copy();
        // 複製一份 KDF 參數（不共用同一個物件：兩個保管庫的標頭各自序列化）
        VariantDictionary kdf = KdbxSerializer.readVariantDictionary(
                KdbxSerializer.serializeVariantDictionary(keySource.header().getKdfParameters()));
        JacksonDatabase db;
        try {
            db = new JacksonDatabase();
        } catch (IOException e) {
            key.wipe();
            throw new IllegalStateException("無法建立空的保管庫：" + e.getClass().getSimpleName());
        }
        KdbxModelAccess.setGroupUuid(db.getRootGroup(), UUID.randomUUID());
        db.getRootGroup().setName(name);
        db.setName(name);
        db.setDescription("");
        CachedKeyHeader header = new CachedKeyHeader(key.cache());
        header.setKdfParameters(kdf);
        return new Vault(db, header, key);
    }

    /**
     * 以主密碼開啟。
     *
     * @param data     檔案的位元組（不會被修改）
     * @param password 主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @return 開啟中的保管庫
     * @throws WrongPasswordException  主密碼錯誤（AC-01-3）
     * @throws UnsupportedKdfException 加密設定不支援，例如 Argon2id（AC-01-7）；不需要主密碼就能判斷
     * @throws CorruptVaultException   不是 KDBX 4 或內容損壞
     * @throws CostlyKdfException      金鑰衍生成本超過 {@link KdfCost#CONFIRM_FACTOR}，要先問使用者（C-4）；沒有跑 KDF
     */
    public Vault open(byte[] data, byte[] password)
            throws WrongPasswordException, UnsupportedKdfException, CorruptVaultException, CostlyKdfException {
        return open(data, password, false);
    }

    /**
     * 以主密碼開啟；{@code costlyConfirmed} 為 {@code true} 時使用者已經看過預估時間並同意（C-4，D-186）。
     *
     * @param data            檔案的位元組（不會被修改）
     * @param password        主密碼（UTF-8）；不會被保留，呼叫端用完清零
     * @param costlyConfirmed 使用者已確認成本過高的金鑰衍生
     * @return 開啟中的保管庫
     * @throws WrongPasswordException  主密碼錯誤
     * @throws UnsupportedKdfException 加密設定不支援
     * @throws CorruptVaultException   不是 KDBX 4 或內容損壞
     * @throws CostlyKdfException      沒有確認而成本超過門檻
     */
    public Vault open(byte[] data, byte[] password, boolean costlyConfirmed)
            throws WrongPasswordException, UnsupportedKdfException, CorruptVaultException, CostlyKdfException {
        return load(data, KeyMaterial.fromPassword(password), null,
                costlyConfirmed ? Double.POSITIVE_INFINITY : KdfCost.CONFIRM_FACTOR);
    }

    // 用已開啟保管庫的金鑰開別的檔案（背景同步、讀回驗證）時沒有人可以問：不比這個保管庫更貴的才開（使用者解鎖時
    // 已經接受過它的成本）；雲端標頭被改得更貴就擋下（C-4）
    private static double ceilingFor(Vault keySource) {
        return Math.max(KdfCost.CONFIRM_FACTOR, KdfCost.factorOf(keySource.format()));
    }

    /**
     * 用另一個開啟中保管庫的金鑰開啟：存檔後的讀回驗證、解密遠端或基準檔（Architecture §5）。
     * KDF 參數（含鹽）相同時不重算 KDF；不同時（例如 KeePassXC 存檔時換了鹽）照常計算一次。
     *
     * @param data      檔案的位元組（不會被修改）
     * @param keySource 提供金鑰的保管庫（必須還沒上鎖）；新保管庫拿的是複本，兩者各自上鎖
     * @return 開啟中的保管庫
     * @throws WrongPasswordException  這個檔案不是用同一個主密碼加密
     * @throws UnsupportedKdfException 加密設定不支援
     * @throws CorruptVaultException   不是 KDBX 4 或內容損壞
     * @throws IllegalStateException   {@code keySource} 已上鎖
     */
    public Vault openWithKeyOf(byte[] data, Vault keySource)
            throws WrongPasswordException, UnsupportedKdfException, CorruptVaultException, CostlyKdfException {
        return load(data, keyOf(keySource), null, ceilingFor(keySource));
    }

    /**
     * 同 {@link #openWithKeyOf(byte[], Vault)}；{@code costlyConfirmed} 為 {@code true} 時不看成本門檻（使用者自己選的本機備份）。
     *
     * @param data            檔案的位元組（不會被修改）
     * @param keySource       提供金鑰的保管庫（必須還沒上鎖）
     * @param costlyConfirmed 使用者已確認（或這是使用者自己的本機備份）
     * @return 開啟中的保管庫
     * @throws WrongPasswordException  不是同一個主密碼
     * @throws UnsupportedKdfException 加密設定不支援
     * @throws CorruptVaultException   不是 KDBX 4 或內容損壞
     * @throws CostlyKdfException      沒有確認而比目前這份更貴
     */
    public Vault openWithKeyOf(byte[] data, Vault keySource, boolean costlyConfirmed)
            throws WrongPasswordException, UnsupportedKdfException, CorruptVaultException, CostlyKdfException {
        return load(data, keyOf(keySource), null,
                costlyConfirmed ? Double.POSITIVE_INFINITY : ceilingFor(keySource));
    }

    /**
     * 和 {@link #openWithKeyOf} 相同，但受保護的欄位不在記憶體中封存（T-29，D-101 修訂）：只給三方合併暫時開啟的基準用
     * （{@code SyncService} 在快照與合併期間持有，結束後立即上鎖）。快照本來就會把每個欄位攤成明文字串，不封存省下
     * 每個密碼一次封存與解封；保護旗標、內容與快照都和一般開啟相同。從這份複製到別的保管庫時一律重新封存
     * （{@link MergeWriter}），所以不封存的值不會進入要寫出或繼續開啟的那一份。不要用在畫面或存檔的保管庫。
     *
     * @param data      檔案的位元組（不會被修改）
     * @param keySource 提供金鑰的保管庫（必須還沒上鎖）
     * @return 開啟中的保管庫，用完立即 {@link Vault#close()}
     * @throws WrongPasswordException  這個檔案不是用同一個主密碼加密
     * @throws UnsupportedKdfException 加密設定不支援
     * @throws CorruptVaultException   不是 KDBX 4 或內容損壞
     * @throws IllegalStateException   {@code keySource} 已上鎖
     */
    public Vault openForMerge(byte[] data, Vault keySource)
            throws WrongPasswordException, UnsupportedKdfException, CorruptVaultException, CostlyKdfException {
        return load(data, keyOf(keySource), UnsealedValue.STRATEGY, ceilingFor(keySource));
    }

    private static KeyMaterial keyOf(Vault keySource) {
        return keySource.key().copy();
    }

    /**
     * 序列化成 KDBX 4 位元組，沿用保管庫的加密設定與 KDF 參數（含鹽，所以不重算 KDF），
     * 但每次都換新主種子、IV 與內層串流金鑰：函式庫預設會沿用讀入時的值，同一把金鑰配同一個
     * IV／nonce 加密不同內容是不安全的。
     *
     * @param vault 開啟中的保管庫
     * @return 檔案位元組
     * @throws IllegalStateException 保管庫已上鎖
     */
    public byte[] serialize(Vault vault) {
        CachedKeyHeader header = vault.header();
        header.setMasterSeed(randomBytes(MASTER_SEED_BYTES));
        boolean chacha = KdbxHeaderScan.CIPHER_CHACHA20.equals(header.getCipherUuid());
        header.setEncryptionIv(randomBytes(chacha ? CHACHA20_NONCE_BYTES : AES_IV_BYTES));
        boolean innerChaCha = header.getProtectedStreamAlgorithm() == Encryption.ProtectedStreamAlgorithm.CHA_CHA_20;
        header.setInnerRandomStreamKey(randomBytes(innerChaCha ? CHACHA20_INNER_KEY_BYTES : SALSA20_INNER_KEY_BYTES));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            vault.database().save(new KdbxStreamFormat(header), vault.key(), out);
        } catch (IOException e) {
            // 寫進記憶體不會有 I/O 錯誤；真的發生表示函式庫狀態有問題
            throw new IllegalStateException("序列化失敗：" + e.getClass().getSimpleName());
        }
        return out.toByteArray();
    }

    /** @param strategy 受保護欄位的存放方式；{@code null} 是函式庫預設（{@code SealedStore} 封存） */
    private static Vault load(byte[] data, KeyMaterial key, PropertyValue.Strategy strategy, double maxFactor)
            throws WrongPasswordException, UnsupportedKdfException, CorruptVaultException, CostlyKdfException {
        try {
            double factor = KdbxHeaderScan.check(data);
            if (factor > maxFactor) {
                throw new CostlyKdfException(factor);
            }
        } catch (UnsupportedKdfException | CorruptVaultException | CostlyKdfException e) {
            key.wipe();
            throw e;
        }
        CachedKeyHeader header = new CachedKeyHeader(key.cache());
        try {
            KdbxStreamFormat format = new KdbxStreamFormat(header);
            JacksonDatabase db;
            if (strategy == null) {
                db = JacksonDatabase.load(format, key, new ByteArrayInputStream(data));
            } else {
                // 同 JacksonDatabase.load，只是讀檔前先換掉欄位值的策略（load 沒有可以指定策略的版本）
                JacksonSerializableDatabase jsd = new JacksonSerializableDatabase();
                jsd.setPropertyValueStrategy(strategy);
                format.load(jsd, key, new ByteArrayInputStream(data));
                db = new JacksonDatabase(jsd.keePassFile, format);
                db.setPropertyValueStrategy(strategy);
            }
            return new Vault(db, header, key);
        } catch (IllegalStateException e) {
            key.wipe();
            if (WRONG_KEY_MESSAGE.equals(e.getMessage())) {
                throw new WrongPasswordException();
            }
            throw new CorruptVaultException("內容無法解析：" + e.getClass().getSimpleName());
        } catch (IOException | RuntimeException e) {
            // 只記類型：函式庫的訊息可能含有解密到一半的 XML 片段
            key.wipe();
            throw new CorruptVaultException("內容無法解析：" + e.getClass().getSimpleName());
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
