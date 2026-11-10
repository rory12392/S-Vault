package app.svault.core.vault;

/**
 * S-Vault 建立保管庫時使用的 Argon2d 參數。開啟其他工具建立的檔案時沿用檔案自己的參數，不使用這裡的值。
 *
 * @param memoryBytes 記憶體（位元組，1 MiB 的倍數）
 * @param iterations  迭代次數
 * @param parallelism 平行度
 */
public record KdfParams(long memoryBytes, long iterations, int parallelism) {

    private static final long MIB = 1024L * 1024L;

    /**
     * AC-01-8 的正式參數：64 MiB、2 次、平行度 4。Pixel 9 Pro debug 版實測解鎖 1.9 秒。
     */
    public static final KdfParams DEFAULT = new KdfParams(64 * MIB, 2, 4);

    /**
     * @throws IllegalArgumentException 參數不合理時
     */
    public KdfParams {
        if (memoryBytes < MIB || memoryBytes % MIB != 0) {
            throw new IllegalArgumentException("記憶體要是 1 MiB 的倍數");
        }
        if (iterations < 1 || parallelism < 1) {
            throw new IllegalArgumentException("迭代次數與平行度至少 1");
        }
    }
}
