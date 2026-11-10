package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 主密碼的字元與位元組轉換、清零（CONVENTIONS §2 機密資料；T-20 補，AC-15-4）。 */
class SecretsTest {

    @Test
    @DisplayName("utf8：中文與符號轉成 UTF-8 位元組，不改動輸入的字元")
    void utf8EncodesWithoutTouchingInput() {
        char[] chars = "主密碼-probe1234!".toCharArray();

        byte[] bytes = Secrets.utf8(chars);

        assertArrayEquals("主密碼-probe1234!".getBytes(StandardCharsets.UTF_8), bytes);
        assertArrayEquals("主密碼-probe1234!".toCharArray(), chars, "呼叫端自己清零");
    }

    @Test
    @DisplayName("utf8：空白主密碼得到空陣列")
    void utf8Empty() {
        assertArrayEquals(new byte[0], Secrets.utf8(new char[0]));
    }

    @Test
    @DisplayName("wipe：位元組與字元都清成 0，null 不做事")
    void wipeClearsAndAcceptsNull() {
        byte[] bytes = {1, 2, 3};
        char[] chars = {'a', 'b'};

        Secrets.wipe(bytes);
        Secrets.wipe(chars);

        assertArrayEquals(new byte[3], bytes);
        assertArrayEquals(new char[2], chars);
        assertDoesNotThrow(() -> Secrets.wipe((byte[]) null));
        assertDoesNotThrow(() -> Secrets.wipe((char[]) null));
    }
}
