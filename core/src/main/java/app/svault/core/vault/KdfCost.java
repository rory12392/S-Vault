package app.svault.core.vault;

/**
 * 金鑰衍生的成本，以 S-Vault 預設（Argon2d 64 MiB、2 次，AC-01-8）為 1 倍（C-4，D-186）。
 *
 * <p>Argon2 的時間約和「記憶體 × 迭代次數」成正比（平行度在手機上幾乎不省時間，不計入）；AES-KDF 以 1,000 萬輪當成
 * 1 倍（現在的電腦約 1 秒，和 KeePassXC 依速度設的預設相近）。只是估計，用來決定要不要先問使用者與顯示預估時間。
 */
public final class KdfCost {

    /** 超過預設的幾倍要先問使用者（KeePassXC 依電腦速度設的合法檔案常在 10 倍以內）。 */
    public static final double CONFIRM_FACTOR = 10.0;

    private static final double DEFAULT_MEMORY_ITERATIONS = (double) KdfParams.DEFAULT.memoryBytes()
            * KdfParams.DEFAULT.iterations();
    private static final double AES_ROUNDS_PER_FACTOR = 10_000_000.0;
    private static final double WEAK_FACTOR = 0.25;
    private static final long MIN_ARGON2_MEMORY = 16L * 1024 * 1024;

    private KdfCost() {
    }

    /**
     * @param format 開啟中保管庫的加密設定
     * @return 成本是預設的幾倍
     */
    public static double factorOf(VaultFormat format) {
        if ("AES-KDF".equals(format.kdf())) {
            return aes(format.iterations());
        }
        return argon2(format.memoryBytes(), format.iterations());
    }

    /**
     * 偏弱（L-4，D-187）：成本不到預設的 1/4（例：KeePass 舊版預設 AES-KDF 6 萬輪），或 Argon2 記憶體小於 16 MiB
     * （迭代再多，GPU 仍然好算）。解鎖後提示，使用者可以一鍵升級成預設（{@code VaultSession#upgradeKdf}）。
     *
     * @param format 開啟中保管庫的加密設定
     * @return 偏弱
     */
    public static boolean isWeak(VaultFormat format) {
        if (factorOf(format) < WEAK_FACTOR) {
            return true;
        }
        return !"AES-KDF".equals(format.kdf()) && format.memoryBytes() < MIN_ARGON2_MEMORY;
    }

    /**
     * 給畫面顯示「大約需要 n 秒」。
     *
     * @param factor          成本是預設的幾倍
     * @param baselineSeconds 這個平台跑一次預設參數大約幾秒（desktop 約 0.5、手機約 2）
     * @return 預估秒數
     */
    public static double estimatedSeconds(double factor, double baselineSeconds) {
        return factor * baselineSeconds;
    }

    static double argon2(long memoryBytes, long iterations) {
        return (double) memoryBytes * iterations / DEFAULT_MEMORY_ITERATIONS;
    }

    static double aes(long rounds) {
        return rounds / AES_ROUNDS_PER_FACTOR;
    }
}
