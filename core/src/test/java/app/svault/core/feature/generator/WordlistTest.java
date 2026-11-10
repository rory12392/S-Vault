package app.svault.core.feature.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link Wordlist}：EFF Large Wordlist 的載入與格式檢查。
 */
class WordlistTest {

    private static Wordlist parse(String text, int expected) throws Exception {
        return Wordlist.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), expected);
    }

    @Test
    @DisplayName("AC-04-2：EFF Large Wordlist 有 7,776 個不重複的單字，頭尾與原檔一致，重複呼叫共用同一份")
    void ac04_2_effLargeLoads() {
        Wordlist eff = Wordlist.effLarge();
        assertEquals(Wordlist.EFF_LARGE_SIZE, eff.size());
        assertEquals("abacus", eff.word(0));
        assertEquals("zoom", eff.word(eff.size() - 1));
        for (int i = 0; i < eff.size(); i++) {
            assertTrue(eff.word(i).matches("[a-z]+(-[a-z]+)*"), eff.word(i));
        }
        assertSame(eff, Wordlist.effLarge());
    }

    @Test
    @DisplayName("故障注入：格式不對、單字重複、字數不符、資源不存在都拋 IllegalStateException")
    void malformedWordlistRejected() {
        assertThrows(IllegalStateException.class, () -> parse("11111 abacus\n", 1));
        assertThrows(IllegalStateException.class, () -> parse("11111\tAbacus\n", 1));
        assertThrows(IllegalStateException.class, () -> parse("11111\tabacus\n11112\tabacus\n", 2));
        assertThrows(IllegalStateException.class, () -> parse("11111\tabacus\n", 2));
        assertThrows(IllegalStateException.class, () -> parse("", 1));
        assertThrows(IllegalStateException.class, () -> Wordlist.loadResource("missing.txt", 1));
    }

    @Test
    @DisplayName("格式正確的小字表可以解析，保留原本的順序")
    void parsesInOrder() throws Exception {
        Wordlist w = parse("11111\tone\n11112\tt-shirt\n", 2);
        assertEquals(2, w.size());
        assertEquals("one", w.word(0));
        assertEquals("t-shirt", w.word(1));
    }
}
