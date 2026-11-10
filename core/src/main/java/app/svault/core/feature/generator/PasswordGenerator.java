package app.svault.core.feature.generator;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 產生隨機密碼（AC-04-1）與通行片語（AC-04-2），亂數只用 {@link SecureRandom}（AC-04-3）。
 *
 * <p>結果以 {@code char[]} 回傳：呼叫端用完（填進欄位或複製後）以 {@code Arrays.fill} 清零
 * （CONVENTIONS §2）。本類別不保留、不記錄產生的值，也沒有會輸出結果的 {@code toString()}。
 *
 * <p>可以從不同執行緒呼叫（{@code SecureRandom} 是執行緒安全的，字表載入有同步）。速度很快
 * （128 字元的密碼遠低於 1 毫秒），平台外殼可以直接在 UI 執行緒呼叫；第一次產生片語時會讀字表（約 100 KB）。
 */
public final class PasswordGenerator {

    private final SecureRandom random;
    private final Supplier<Wordlist> wordlist;

    /** 用系統預設的 {@link SecureRandom} 與 EFF Large Wordlist（第一次產生片語時才載入）。 */
    public PasswordGenerator() {
        this(new SecureRandom(), Wordlist::effLarge);
    }

    /**
     * @param random   亂數來源；型別限定 {@link SecureRandom}，不接受一般的 {@code java.util.Random}（AC-04-3）
     * @param wordlist 通行片語的字表（延遲取得）
     */
    PasswordGenerator(SecureRandom random, Supplier<Wordlist> wordlist) {
        this.random = Objects.requireNonNull(random);
        this.wordlist = Objects.requireNonNull(wordlist);
    }

    /**
     * 產生隨機密碼：先從每個勾選的種類各取一個字元，其餘從所有勾選種類的聯集取，最後整串洗牌，
     * 讓「保證出現的字元」不固定在開頭。
     *
     * @param options 長度與字元種類
     * @return 新的密碼；呼叫端用完清零
     */
    public char[] password(PasswordOptions options) {
        StringBuilder poolBuilder = new StringBuilder();
        char[] out = new char[options.length()];
        int filled = 0;
        for (CharClass c : CharClass.values()) {
            if (options.classes().contains(c)) {
                out[filled++] = pick(c.alphabet());
                poolBuilder.append(c.alphabet());
            }
        }
        // 字元集是公開的固定字串，放在 String 裡沒有機密問題
        String pool = poolBuilder.toString();
        while (filled < out.length) {
            out[filled++] = pick(pool);
        }
        shuffle(out);
        return out;
    }

    /**
     * 產生通行片語：從字表等機率、可重複地取 {@code wordCount} 個單字，以分隔符號串起來。
     * 可重複才是每字獨立的 log2(字數) 位元熵；不重複反而會讓攻擊者少猜一些組合。
     *
     * @param options 單字數與分隔符號
     * @return 新的片語；呼叫端用完清零
     * @throws IllegalStateException 字表無法載入（打包錯誤）
     */
    public char[] passphrase(PassphraseOptions options) {
        Wordlist list = wordlist.get();
        String[] picked = new String[options.wordCount()];
        int total = options.separator().length() * (picked.length - 1);
        for (int i = 0; i < picked.length; i++) {
            picked[i] = list.word(random.nextInt(list.size()));
            total += picked[i].length();
        }
        // 直接組進 char[]，不經 StringBuilder／String：整串片語是機密，不在堆積裡留下無法清除的副本
        char[] out = new char[total];
        int pos = 0;
        // 不分隔時每個單字開頭大寫（I-3）：否則 at＋tend 和 attend 看起來一樣，同一串可能有好幾種拆法，實際的熵比
        // 「單字數 × log2(字表大小)」低；大寫標出邊界，每一串只有一種拆法
        boolean capitalize = options.separator().isEmpty();
        for (int i = 0; i < picked.length; i++) {
            if (i > 0) {
                pos = copy(options.separator(), out, pos);
            }
            int start = pos;
            pos = copy(picked[i], out, pos);
            if (capitalize && pos > start) {
                out[start] = Character.toUpperCase(out[start]);
            }
        }
        Arrays.fill(picked, null);
        return out;
    }

    private char pick(String alphabet) {
        // SecureRandom.nextInt(bound) 以拒絕取樣產生 [0, bound) 的均勻整數（Random#nextInt(int) 的規格），
        // 不是 nextInt() % bound，所以沒有取模偏差
        return alphabet.charAt(random.nextInt(alphabet.length()));
    }

    /** Fisher–Yates 洗牌：每種排列等機率。 */
    private void shuffle(char[] chars) {
        for (int i = chars.length - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            char tmp = chars[i];
            chars[i] = chars[j];
            chars[j] = tmp;
        }
    }

    private static int copy(String s, char[] dest, int pos) {
        s.getChars(0, s.length(), dest, pos);
        return pos + s.length();
    }
}
