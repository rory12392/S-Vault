package app.svault.android.shell.start;

import android.text.Editable;

/**
 * 從輸入框取出主密碼字元，不經過 {@code String}（CONVENTIONS §2 機密資料）。轉成 UTF-8 與清零用 core 的
 * {@code app.svault.core.shell.Secrets}。
 */
final class PasswordChars {

    private PasswordChars() {
    }

    /**
     * @param text 輸入框的內容（{@code TextInputEditText.getText()}）；{@code null} 時視為空的
     * @return 字元的新陣列；呼叫端用完以 {@code Secrets.wipe(char[])} 清零
     */
    static char[] of(Editable text) {
        if (text == null) {
            return new char[0];
        }
        char[] out = new char[text.length()];
        text.getChars(0, out.length, out, 0);
        return out;
    }
}
