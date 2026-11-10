package app.svault.core.feature.generator;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通行片語的字表（AC-04-2）。正式使用 EFF Large Wordlist（7,776 字，CC BY 3.0，
 * https://www.eff.org/dice；授權聲明見 {@code NOTICES.md}），檔案原樣放在資源
 * {@code app/svault/core/feature/generator/eff_large_wordlist.txt}，格式是每行「五位骰子號碼＋Tab＋單字」。
 *
 * <p>載入時檢查格式、字數與不重複：字表少了或重複，每個單字的熵就不是 log2(7776) ≈ 12.9 位元，
 * 寧可讓產生器顯示錯誤，也不默默產生比畫面宣稱弱的片語。
 */
public final class Wordlist {

    /** EFF Large Wordlist 的字數（6^5）。 */
    public static final int EFF_LARGE_SIZE = 7776;

    static final String EFF_LARGE_RESOURCE = "eff_large_wordlist.txt";

    private static final Pattern LINE = Pattern.compile("[1-6]{5}\t([a-z]+(?:-[a-z]+)*)");

    /** 延遲載入：只產生隨機密碼的使用者不必付讀字表的成本。 */
    private static Wordlist effLarge;

    private final List<String> words;

    private Wordlist(List<String> words) {
        this.words = List.copyOf(words);
    }

    /**
     * 第一次呼叫時從資源載入（約 100 KB），之後重複使用同一份。
     *
     * @return EFF Large Wordlist
     * @throws IllegalStateException 資源不存在或內容不正確（打包錯誤）
     */
    public static synchronized Wordlist effLarge() {
        // 不用 holder 類別：靜態初始化失敗後只剩沒有原因的 NoClassDefFoundError，之後也無法重試；
        // 這裡失敗時每次都拋帶原因的 IllegalStateException，下次呼叫會再讀一次
        if (effLarge == null) {
            effLarge = loadResource(EFF_LARGE_RESOURCE, EFF_LARGE_SIZE);
        }
        return effLarge;
    }

    /**
     * 解析 EFF 格式的字表。
     *
     * @param in           字表內容（UTF-8）；呼叫端負責關閉
     * @param expectedSize 應有的字數
     * @return 字表
     * @throws IOException           讀取失敗
     * @throws IllegalStateException 格式不對、字數不符或有重複的單字
     */
    static Wordlist parse(InputStream in, int expectedSize) throws IOException {
        List<String> words = new ArrayList<>(expectedSize);
        Set<String> seen = new HashSet<>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                throw new IllegalStateException("字表第 " + lineNo + " 行格式不對");
            }
            String word = m.group(1);
            if (!seen.add(word)) {
                throw new IllegalStateException("字表第 " + lineNo + " 行的單字重複");
            }
            words.add(word);
        }
        if (words.size() != expectedSize) {
            throw new IllegalStateException("字表應有 " + expectedSize + " 個單字，實際 " + words.size() + " 個");
        }
        return new Wordlist(words);
    }

    static Wordlist loadResource(String name, int expectedSize) {
        try (InputStream in = Wordlist.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("找不到字表資源：" + name);
            }
            return parse(in, expectedSize);
        } catch (IOException e) {
            throw new UncheckedIOException("讀取字表資源失敗：" + name, e);
        }
    }

    /**
     * @return 字數
     */
    public int size() {
        return words.size();
    }

    /**
     * @param index 0 到 {@link #size()}－1
     * @return 單字（小寫 ASCII，少數含連字號，例如 {@code t-shirt}）
     */
    public String word(int index) {
        return words.get(index);
    }
}
