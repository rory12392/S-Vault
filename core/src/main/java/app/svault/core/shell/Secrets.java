package app.svault.core.shell;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 主密碼的字元與位元組轉換（CONVENTIONS §2 機密資料）。轉換過程不經過 {@code String}，
 * 中間的緩衝區用完立刻清零。兩平台共用（T-14 從雙胞胎檔案搬來）；從輸入框取出字元由各平台自己做。
 */
public final class Secrets {

    private Secrets() {
    }

    /**
     * @param chars 主密碼字元；不會被修改，呼叫端自行清零
     * @return UTF-8 位元組的新陣列；呼叫端用完以 {@link #wipe(byte[])} 清零
     */
    public static byte[] utf8(char[] chars) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
        byte[] out = new byte[encoded.remaining()];
        encoded.get(out);
        // encode 配置的緩衝區可能比內容大，整個清掉
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return out;
    }

    /** @param bytes 要清零的陣列；{@code null} 時不做事 */
    public static void wipe(byte[] bytes) {
        if (bytes != null) {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    /** @param chars 要清零的陣列；{@code null} 時不做事 */
    public static void wipe(char[] chars) {
        if (chars != null) {
            Arrays.fill(chars, '\0');
        }
    }
}
