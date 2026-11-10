package app.svault.core.feature.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PasswordGenerator} 的隨機密碼（AC-04-1）與亂數品質（AC-04-3）。通行片語見 {@link PassphraseTest}。
 */
class PasswordGeneratorTest {

    private final PasswordGenerator generator = new PasswordGenerator();

    /** 四種字元種類的全部非空組合（15 種）。 */
    private static List<Set<CharClass>> allNonEmptySubsets() {
        CharClass[] all = CharClass.values();
        List<Set<CharClass>> subsets = new ArrayList<>();
        for (int mask = 1; mask < (1 << all.length); mask++) {
            Set<CharClass> s = EnumSet.noneOf(CharClass.class);
            for (int i = 0; i < all.length; i++) {
                if ((mask & (1 << i)) != 0) {
                    s.add(all[i]);
                }
            }
            subsets.add(s);
        }
        return subsets;
    }

    @Test
    @DisplayName("AC-04-1：長度 8–128 的每種字元組合，長度正確、每種勾選的種類至少一個、沒有未勾選的字元")
    void ac04_1_lengthAndClasses() {
        int[] lengths = {8, 9, 16, 20, 64, 127, 128};
        for (Set<CharClass> classes : allNonEmptySubsets()) {
            for (int length : lengths) {
                // 長度 8、四種全選時每種剛好可能只出現一次，多跑幾次讓洗牌與隨機補位都走到
                for (int run = 0; run < 50; run++) {
                    char[] pw = generator.password(new PasswordOptions(length, classes));
                    assertEquals(length, pw.length);
                    for (CharClass c : CharClass.values()) {
                        boolean present = false;
                        for (char ch : pw) {
                            present |= c.contains(ch);
                        }
                        assertEquals(classes.contains(c), present, () -> c + " 的出現與否不符，選項 " + classes);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("AC-04-1：長度超出 8–128 或沒有勾選字元種類時拒絕")
    void ac04_1_invalidOptionsRejected() {
        assertThrows(IllegalArgumentException.class, () -> PasswordOptions.allClasses(7));
        assertThrows(IllegalArgumentException.class, () -> PasswordOptions.allClasses(129));
        assertThrows(IllegalArgumentException.class, () -> PasswordOptions.allClasses(0));
        assertThrows(IllegalArgumentException.class, () -> new PasswordOptions(16, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new PasswordOptions(16, null));
    }

    @Test
    @DisplayName("AC-04-1：選項保存的字元種類是不可修改的副本")
    void ac04_1_optionsAreImmutable() {
        Set<CharClass> classes = EnumSet.of(CharClass.LOWER);
        PasswordOptions options = new PasswordOptions(12, classes);
        classes.add(CharClass.DIGIT);
        assertEquals(Set.of(CharClass.LOWER), options.classes());
        assertThrows(UnsupportedOperationException.class, () -> options.classes().add(CharClass.UPPER));
    }

    @Test
    @DisplayName("AC-04-3：連續產生 10,000 個 16 字元密碼不重複")
    void ac04_3_tenThousandUnique() {
        PasswordOptions options = PasswordOptions.allClasses(16);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertTrue(seen.add(new String(generator.password(options))), "第 " + i + " 個密碼重複");
        }
        assertEquals(10_000, seen.size());
    }

    @Test
    @DisplayName("AC-04-3：字元分布均勻（沒有取模偏差）：只選數字時每個數字約 10%")
    void ac04_3_uniformDistribution() {
        // 20,000 × 8 = 160,000 個數字；每個期望 16,000、標準差約 120，容許 ±5%（約 6.7 個標準差，不會偶發失敗）
        int[] counts = new int[10];
        PasswordOptions options = new PasswordOptions(8, EnumSet.of(CharClass.DIGIT));
        for (int i = 0; i < 20_000; i++) {
            for (char c : generator.password(options)) {
                counts[c - '0']++;
            }
        }
        for (int d = 0; d < 10; d++) {
            assertTrue(Math.abs(counts[d] - 16_000) < 800, "數字 " + d + " 出現 " + counts[d] + " 次");
        }
    }

    @Test
    @DisplayName("AC-04-1：保證出現的字元有洗牌，不固定在開頭（第一個字元不總是大寫）")
    void ac04_1_guaranteedCharsAreShuffled() {
        // 沒洗牌時第一個字元一定是大寫（先放 UPPER）；洗牌後機率約 26/94＋保證那一個的位置 ≈ 0.3
        PasswordOptions options = PasswordOptions.allClasses(8);
        int upperFirst = 0;
        for (int i = 0; i < 2_000; i++) {
            if (CharClass.UPPER.contains(generator.password(options)[0])) {
                upperFirst++;
            }
        }
        assertTrue(upperFirst < 1_200, "第一個字元是大寫 " + upperFirst + "／2000 次");
        assertTrue(upperFirst > 200, "第一個字元是大寫 " + upperFirst + "／2000 次");
    }

    @Test
    @DisplayName("AC-04-3：可注入的亂數來源型別限定為 SecureRandom；預設建構子不需要字表")
    void ac04_3_secureRandomOnly() throws Exception {
        assertEquals(SecureRandom.class,
                PasswordGenerator.class.getDeclaredField("random").getType());
        // 只產生密碼時不讀字表：字表 supplier 被呼叫就失敗
        PasswordGenerator noWordlist = new PasswordGenerator(new SecureRandom(), () -> {
            throw new AssertionError("產生密碼不應該讀字表");
        });
        assertEquals(16, noWordlist.password(PasswordOptions.allClasses(16)).length);
    }

    @Test
    @DisplayName("符號種類是 ASCII 的 32 個標點，四種字元集互不重疊")
    void alphabetsAreDisjointAscii() {
        assertEquals(32, CharClass.SYMBOL.alphabet().length());
        Set<Character> all = new HashSet<>();
        int total = 0;
        for (CharClass c : CharClass.values()) {
            for (char ch : c.alphabet().toCharArray()) {
                assertTrue(ch > 0x20 && ch < 0x7F, "不是可列印的 ASCII：" + (int) ch);
                all.add(ch);
                total++;
            }
        }
        assertEquals(94, total);
        assertEquals(94, all.size());
    }
}
