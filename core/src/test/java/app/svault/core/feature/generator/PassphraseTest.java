package app.svault.core.feature.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PasswordGenerator#passphrase}：4–10 個單字、可選分隔符號（AC-04-2）。
 */
class PassphraseTest {

    /** 測試用的小字表：單字不含任何分隔符號，任何分隔都能拆回來。 */
    private static Wordlist tinyWordlist() throws IOException {
        String text = "11111\talpha\n11112\tbravo\n11113\tcharlie\n11114\tdelta\n";
        return Wordlist.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), 4);
    }

    @Test
    @DisplayName("AC-04-2：4–10 個單字配每種分隔符號，字數正確、單字都來自字表、以指定符號分隔")
    void ac04_2_wordCountAndSeparator() throws IOException {
        Wordlist tiny = tinyWordlist();
        Set<String> vocabulary = Set.of("alpha", "bravo", "charlie", "delta");
        PasswordGenerator generator = new PasswordGenerator(new SecureRandom(), () -> tiny);
        for (String separator : PassphraseOptions.SEPARATORS) {
            for (int words = PassphraseOptions.MIN_WORDS; words <= PassphraseOptions.MAX_WORDS; words++) {
                String phrase = new String(generator.passphrase(new PassphraseOptions(words, separator)));
                List<String> parts = separator.isEmpty() ? splitCapitalized(phrase)
                        : List.of(phrase.split(Pattern.quote(separator), -1));
                assertEquals(words, parts.size(), "「" + separator + "」：" + phrase);
                assertTrue(vocabulary.containsAll(parts), phrase);
            }
        }
    }

    /** 不分隔時每個單字開頭大寫（I-3），在大寫字母前拆開、轉回小寫。 */
    private static List<String> splitCapitalized(String phrase) {
        List<String> parts = new ArrayList<>();
        for (String p : phrase.split("(?=\\p{Lu})")) {
            assertTrue(Character.isUpperCase(p.charAt(0)), "每個單字開頭大寫：" + phrase);
            parts.add(p.toLowerCase(java.util.Locale.ROOT));
        }
        return parts;
    }

    @Test
    @DisplayName("I-3：不分隔時單字開頭大寫，邊界不會模糊（at＋tend 不會和 attend 混在一起，熵不打折）")
    void i3_noSeparatorKeepsWordBoundaries() {
        PasswordGenerator generator = new PasswordGenerator();
        for (int i = 0; i < 100; i++) {
            String phrase = new String(generator.passphrase(new PassphraseOptions(6, "")));
            assertEquals(6, phrase.split("(?=\\p{Lu})").length, phrase);
        }
    }

    @Test
    @DisplayName("AC-04-2：用正式的 EFF 字表產生片語，每個單字都在字表裡")
    void ac04_2_effWordlist() {
        Wordlist eff = Wordlist.effLarge();
        Set<String> vocabulary = new HashSet<>();
        for (int i = 0; i < eff.size(); i++) {
            vocabulary.add(eff.word(i));
        }
        PasswordGenerator generator = new PasswordGenerator();
        for (int i = 0; i < 200; i++) {
            String phrase = new String(generator.passphrase(new PassphraseOptions(6, " ")));
            String[] parts = phrase.split(" ", -1);
            assertEquals(6, parts.length, phrase);
            for (String p : parts) {
                assertTrue(vocabulary.contains(p), p);
            }
        }
    }

    @Test
    @DisplayName("AC-04-2：單字數超出 4–10 或分隔符號不在清單時拒絕")
    void ac04_2_invalidOptionsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new PassphraseOptions(3, "-"));
        assertThrows(IllegalArgumentException.class, () -> new PassphraseOptions(11, "-"));
        assertThrows(IllegalArgumentException.class, () -> new PassphraseOptions(5, "x"));
        assertThrows(IllegalArgumentException.class, () -> new PassphraseOptions(5, "\n"));
        assertThrows(IllegalArgumentException.class, () -> new PassphraseOptions(5, null));
    }

    @Test
    @DisplayName("AC-04-3：每個位置的單字等機率（4 字的字表各約 25%），且可重複出現")
    void ac04_3_wordsUniformAndIndependent() throws IOException {
        Wordlist tiny = tinyWordlist();
        PasswordGenerator generator = new PasswordGenerator(new SecureRandom(), () -> tiny);
        Map<String, Integer> counts = new HashMap<>();
        boolean sawRepeat = false;
        // 10,000 × 4 = 40,000 個單字；每個期望 10,000、標準差約 87，容許 ±5%（約 5.7 個標準差）
        for (int i = 0; i < 10_000; i++) {
            String[] parts = new String(generator.passphrase(new PassphraseOptions(4, "-"))).split("-");
            sawRepeat |= new HashSet<>(List.of(parts)).size() < parts.length;
            for (String p : parts) {
                counts.merge(p, 1, Integer::sum);
            }
        }
        assertTrue(sawRepeat, "取字應該可重複（每字獨立）");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            assertTrue(Math.abs(e.getValue() - 10_000) < 500, e.getKey() + " 出現 " + e.getValue() + " 次");
        }
    }

    @Test
    @DisplayName("故障注入：字表載入失敗時拋 IllegalStateException，由平台的 ModuleGuard 接住顯示錯誤狀態")
    void wordlistFailurePropagates() {
        PasswordGenerator broken = new PasswordGenerator(new SecureRandom(), () -> {
            throw new IllegalStateException("找不到字表資源");
        });
        assertThrows(IllegalStateException.class, () -> broken.passphrase(new PassphraseOptions(5, "-")));
        // 隨機密碼不受影響
        assertEquals(20, broken.password(PasswordOptions.allClasses(20)).length);
    }
}
