package app.svault.core.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 遠端雜湊（Architecture §5 步驟 1、7）：檔案位元組的 SHA-256。用的是 JDK 內建的 {@code MessageDigest}，不自己實作演算法。
 */
public final class Hashes {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Hashes() {
    }

    /**
     * @param data 檔案位元組
     * @return SHA-256 的小寫十六進位（64 字元）
     */
    public static String sha256(byte[] data) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // 每個 Java 平台都必須提供 SHA-256（Java SE 規格），不會發生
            throw new IllegalStateException("沒有 SHA-256", e);
        }
        byte[] hash = digest.digest(data);
        // HexFormat 在 Android API 29 不存在，手動轉換
        char[] out = new char[hash.length * 2];
        for (int i = 0; i < hash.length; i++) {
            out[i * 2] = HEX[(hash[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[hash[i] & 0xF];
        }
        return new String(out);
    }
}
