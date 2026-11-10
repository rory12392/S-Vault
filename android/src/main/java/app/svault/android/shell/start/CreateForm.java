package app.svault.android.shell.start;

import androidx.annotation.StringRes;
import app.svault.android.R;
import app.svault.core.shell.Secrets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 建立保管庫表單的驗證（AC-01-4、AC-01-5）。不依賴畫面，畫面只負責把結果顯示在對應欄位。
 *
 * <p>和 desktop 不同，android 的存放位置在驗證通過後才以另存新檔選擇器選：選擇器一選就在 Drive 上建立檔案，
 * 先選位置的話，使用者中途放棄就會留下空檔案（D-56）。
 *
 * @param name         保管庫名稱
 * @param password     主密碼（不會被修改或保留）
 * @param confirm      再次輸入的主密碼（不會被修改或保留）
 * @param acknowledged 是否勾選「我了解：忘記主密碼將無法復原資料」
 */
record CreateForm(String name, char[] password, char[] confirm, boolean acknowledged) {

    /** 表單欄位（錯誤顯示的位置，也是送出時焦點移動的順序）。 */
    enum Field { NAME, PASSWORD, CONFIRM, ACKNOWLEDGE }

    /**
     * @param field      出錯的欄位
     * @param messageRes 錯誤文字
     */
    record Problem(Field field, @StringRes int messageRes) { }

    /** 建立的檔案副檔名。 */
    static final String EXTENSION = ".kdbx";

    /** @return 依欄位順序排列的錯誤；沒有錯誤時空清單 */
    List<Problem> validate() {
        List<Problem> out = new ArrayList<>();
        if (name == null || name.trim().isEmpty()) {
            out.add(new Problem(Field.NAME, R.string.create_error_name));
        }
        if (password == null || password.length == 0) {
            out.add(new Problem(Field.PASSWORD, R.string.create_error_empty));
        } else if (!Arrays.equals(password, confirm)) {
            out.add(new Problem(Field.CONFIRM, R.string.create_error_mismatch));
        }
        if (!acknowledged) {
            out.add(new Problem(Field.ACKNOWLEDGE, R.string.create_error_acknowledge));
        }
        return List.copyOf(out);
    }

    /**
     * 驗證並取出要建立的主密碼。不論結果，兩個主密碼字元陣列都會被清零（先驗證、後清零：兩次輸入的比對要用到）。
     *
     * @return 主密碼的 UTF-8 位元組（呼叫端用完清零）；表單有錯時為 {@code null}，錯誤用 {@link #validate()} 取得
     */
    byte[] takePassword() {
        try {
            return validate().isEmpty() ? Secrets.utf8(password) : null;
        } finally {
            Secrets.wipe(password);
            Secrets.wipe(confirm);
        }
    }

    /**
     * @param name 保管庫名稱
     * @return 另存新檔選擇器的預設檔名（名稱＋{@code .kdbx}，已有副檔名時不重複加）
     */
    static String fileName(String name) {
        String n = name.trim();
        return n.toLowerCase(Locale.ROOT).endsWith(EXTENSION) ? n : n + EXTENSION;
    }

    // record 預設的 toString 會列出每個欄位；主密碼欄位不出現在任何字串裡（CONVENTIONS §2）
    @Override
    public String toString() {
        return "CreateForm[name=" + name + "]";
    }
}
