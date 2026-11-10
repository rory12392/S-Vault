package app.svault.core.shell;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 兩平台外殼共用的文字處理：畫面上的時間格式（{@code yyyy/MM/dd HH:mm[:ss]}）與去掉前後空白。
 */
public final class Texts {

    /**
     * 一般的時間（最近開啟、條目的最後修改）。{@link Locale#ROOT}：數字一律是 0–9，不受裝置語系影響。
     * 呼叫端以 {@code withZone(裝置時區)} 或 {@code atZone} 決定時區。
     */
    public static final DateTimeFormatter MINUTES = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.ROOT);

    /** 到秒的時間（備份清單：同一分鐘可能有好幾份）。 */
    public static final DateTimeFormatter SECONDS = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss", Locale.ROOT);

    private Texts() {
    }

    /**
     * 同 JDK 11 的 {@code String.strip()}（以 {@link Character#isWhitespace} 判斷，含全形空白）；
     * Android API 29 沒有 {@code strip()}，{@code trim()} 又不去全形空白。
     *
     * @param s 字串
     * @return 去掉前後空白的字串
     */
    public static String strip(String s) {
        int start = 0;
        int end = s.length();
        while (start < end) {
            int cp = s.codePointAt(start);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            start += Character.charCount(cp);
        }
        while (end > start) {
            int cp = s.codePointBefore(end);
            if (!Character.isWhitespace(cp)) {
                break;
            }
            end -= Character.charCount(cp);
        }
        return s.substring(start, end);
    }
}
