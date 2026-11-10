package app.svault.core.feature.generator;

import java.util.EnumSet;
import java.util.Set;

/**
 * 隨機密碼的選項（AC-04-1）：長度 8–128、至少一種字元種類。
 *
 * @param length  密碼長度（{@link #MIN_LENGTH}–{@link #MAX_LENGTH}）
 * @param classes 勾選的字元種類（不可為空）；每種至少出現一次
 */
public record PasswordOptions(int length, Set<CharClass> classes) {

    /** 最短長度（AC-04-1）。 */
    public static final int MIN_LENGTH = 8;
    /** 最長長度（AC-04-1）。 */
    public static final int MAX_LENGTH = 128;

    /**
     * @throws IllegalArgumentException 長度超出範圍或沒有勾選任何字元種類
     */
    public PasswordOptions {
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            throw new IllegalArgumentException("長度必須在 " + MIN_LENGTH + "–" + MAX_LENGTH + " 之間：" + length);
        }
        if (classes == null || classes.isEmpty()) {
            throw new IllegalArgumentException("至少要勾選一種字元種類");
        }
        // 最短 8 ≥ 種類數 4，所以「每種至少一個」一定放得下，不必另外檢查
        classes = Set.copyOf(EnumSet.copyOf(classes));
    }

    /**
     * @param length 密碼長度
     * @return 四種字元都勾選的選項
     * @throws IllegalArgumentException 長度超出範圍
     */
    public static PasswordOptions allClasses(int length) {
        return new PasswordOptions(length, EnumSet.allOf(CharClass.class));
    }
}
