package app.svault.core.feature.generator;

/**
 * 隨機密碼的字元種類（AC-04-1）。字元集只用 ASCII 可列印字元：各網站與輸入法都打得出來，
 * 也不會因為正規化（NFC／NFKC）變成另一串位元組。
 */
public enum CharClass {
    /** 大寫英文字母（26 個）。 */
    UPPER("ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
    /** 小寫英文字母（26 個）。 */
    LOWER("abcdefghijklmnopqrstuvwxyz"),
    /** 數字（10 個）。 */
    DIGIT("0123456789"),
    /** 符號：ASCII 全部 32 個標點（同 KeePass 的「特殊字元」），不含空白。 */
    SYMBOL("!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~");

    private final String alphabet;

    CharClass(String alphabet) {
        this.alphabet = alphabet;
    }

    /**
     * @return 這個種類的全部字元（不重複）
     */
    public String alphabet() {
        return alphabet;
    }

    /**
     * @param c 字元
     * @return 這個字元是否屬於本種類
     */
    public boolean contains(char c) {
        return alphabet.indexOf(c) >= 0;
    }
}
