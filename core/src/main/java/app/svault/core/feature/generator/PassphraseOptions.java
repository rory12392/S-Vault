package app.svault.core.feature.generator;

import java.util.List;

/**
 * 通行片語的選項（AC-04-2）：4–10 個單字、可選分隔符號。
 *
 * @param wordCount 單字數（{@link #MIN_WORDS}–{@link #MAX_WORDS}）
 * @param separator 單字之間的分隔符號：{@link #SEPARATORS} 其中之一（空字串＝不分隔）
 */
public record PassphraseOptions(int wordCount, String separator) {

    /** 最少單字數（AC-04-2）。 */
    public static final int MIN_WORDS = 4;
    /** 最多單字數（AC-04-2）。 */
    public static final int MAX_WORDS = 10;

    /**
     * 可選的分隔符號，依畫面上的排列順序：連字號、空白、句點、底線、不分隔。只開放固定幾種：
     * 字母或數字當分隔會和單字黏在一起，看不出邊界；固定清單也讓兩個平台的選項一致。
     */
    public static final List<String> SEPARATORS = List.of("-", " ", ".", "_", "");

    /**
     * @throws IllegalArgumentException 單字數超出範圍或分隔符號不在 {@link #SEPARATORS}
     */
    public PassphraseOptions {
        if (wordCount < MIN_WORDS || wordCount > MAX_WORDS) {
            throw new IllegalArgumentException("單字數必須在 " + MIN_WORDS + "–" + MAX_WORDS + " 之間：" + wordCount);
        }
        if (separator == null || !SEPARATORS.contains(separator)) {
            throw new IllegalArgumentException("不支援的分隔符號");
        }
    }
}
