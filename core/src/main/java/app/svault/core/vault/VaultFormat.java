package app.svault.core.vault;

/**
 * 保管庫檔案的加密設定（讀自外層與內層標頭），給 AC-01-8 的檢查與「關於這個保管庫」之類的顯示用。
 *
 * @param kdbxVersion  KDBX 主版本，S-Vault 只開啟 4
 * @param cipher       外層加密：{@code AES-256} 或 {@code ChaCha20}
 * @param kdf          金鑰衍生：{@code Argon2d} 或 {@code AES-KDF}
 * @param memoryBytes  Argon2 記憶體（位元組）；AES-KDF 時為 0
 * @param iterations   Argon2 迭代次數；AES-KDF 時為回合數
 * @param parallelism  Argon2 平行度；AES-KDF 時為 0
 * @param innerStream  受保護欄位的內層串流：{@code ChaCha20} 或 {@code Salsa20}
 */
public record VaultFormat(int kdbxVersion, String cipher, String kdf, long memoryBytes, long iterations,
                          int parallelism, String innerStream) {
}
