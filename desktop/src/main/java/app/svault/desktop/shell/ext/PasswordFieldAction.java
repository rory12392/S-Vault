package app.svault.desktop.shell.ext;

import java.util.function.Supplier;

/**
 * 編輯畫面密碼欄旁的按鈕（例如 GEN 的「產生」）。外殼把結果填進密碼欄後會把陣列清零。
 *
 * @param label  按鈕文字
 * @param action 執行動作，回傳要填入的新密碼；不填入時回傳 {@code null}
 */
public record PasswordFieldAction(String label, Supplier<char[]> action) { }
