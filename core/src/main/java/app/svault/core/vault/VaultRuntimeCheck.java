package app.svault.core.vault;

import app.svault.core.security.PasswordStrength;
import app.svault.core.security.StrengthEstimator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;

/**
 * 執行環境自我檢查：確認 KDBX 需要的函式庫在目前的執行環境（打包後的 desktop、Android）載得到。
 * 打包後缺類別是「開發模式能跑、打包後不能跑」最常見的原因（STACK §7 第 5 點）。
 *
 * <p>T-01 檢查 Woodstox（StAX 實作）、Bouncy Castle（Argon2）與 KeePassJava2；T-04 加上在記憶體中建立、
 * 序列化、重新開啟測試保管庫；T-09 加上密碼強度（zxcvbn 的字典資源）；合併與實際存檔在 T-05、T-13 補上。
 */
public final class VaultRuntimeCheck {

    private VaultRuntimeCheck() {
    }

    /**
     * 一項檢查的結果。
     *
     * @param name   檢查名稱
     * @param ok     是否通過
     * @param detail 通過時為實作類別名稱；失敗時為例外類型（不含任何機密）
     */
    public record Result(String name, boolean ok, String detail) { }

    /**
     * 執行全部檢查，任何一項失敗都不中斷其他檢查。
     *
     * @return 每項檢查的結果
     */
    public static List<Result> run() {
        List<Result> results = new ArrayList<>();
        results.add(check("StAX 讀取（Woodstox）", () -> XMLInputFactory.newFactory().getClass().getName()));
        results.add(check("StAX 寫出（Woodstox）", () -> XMLOutputFactory.newFactory().getClass().getName()));
        results.add(check("Argon2（Bouncy Castle）", VaultRuntimeCheck::argon2));
        // 建立空的資料庫會走一次 Jackson＋StAX 解析內建範本，比只載入類別更接近實際使用
        results.add(check("KeePassJava2（建立空資料庫）", () -> new JacksonDatabase().getClass().getName()));
        results.add(check("保管庫（建立、存檔、重新開啟）", VaultRuntimeCheck::vaultRoundTrip));
        results.add(check("密碼強度（zxcvbn 字典）", VaultRuntimeCheck::strength));
        return List.copyOf(results);
    }

    /**
     * @param results {@link #run()} 的結果
     * @return 全部通過時為 {@code true}
     */
    public static boolean allPassed(List<Result> results) {
        return results.stream().allMatch(Result::ok);
    }

    private static String argon2() {
        // 用極小的參數實際算一次，確認不只類別存在、也真的能執行
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_d)
                .withSalt(new byte[16]).withIterations(1).withMemoryAsKB(8).withParallelism(1).build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(params);
        byte[] out = new byte[32];
        generator.generateBytes(new byte[] {1, 2, 3}, out);
        return Argon2BytesGenerator.class.getName();
    }

    private static String vaultRoundTrip() throws VaultException {
        // 只在記憶體中，用最低的 KDF 參數與假的主密碼：檢查的是打包後整條 VaultCodec 路徑都載得到、跑得動，
        // 不是 KDF 的效能（Architecture §10）
        VaultCodec codec = new VaultCodec(new KdfParams(1024L * 1024L, 1, 1));
        byte[] password = {'s', 'e', 'l', 'f', '-', 'c', 'h', 'e', 'c', 'k'};
        try (Vault created = codec.create("self-check", password)) {
            byte[] bytes = codec.serialize(created);
            try (Vault reopened = codec.open(bytes, password)) {
                if (!reopened.id().equals(created.id())) {
                    throw new IllegalStateException("重新開啟後保管庫 ID 不同");
                }
            }
        } finally {
            Arrays.fill(password, (byte) 0);
        }
        return VaultCodec.class.getName();
    }

    private static String strength() {
        // zxcvbn 的字典是 jar 裡的資源檔，打包（jpackage、APK 的資源合併）後讀不到時評估會失敗或全部變成「強」
        if (new StrengthEstimator().estimate("password") != PasswordStrength.WEAK) {
            throw new IllegalStateException("常見密碼沒有被評為弱");
        }
        return StrengthEstimator.class.getName();
    }

    private interface Probe {
        String get() throws Exception;
    }

    private static Result check(String name, Probe probe) {
        try {
            return new Result(name, true, probe.get());
        } catch (Exception | LinkageError e) {
            return new Result(name, false, e.getClass().getName());
        }
    }
}
