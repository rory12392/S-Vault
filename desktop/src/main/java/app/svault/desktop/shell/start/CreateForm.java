package app.svault.desktop.shell.start;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 建立保管庫表單的驗證（AC-01-4、AC-01-5）。不依賴 JavaFX，畫面只負責把結果顯示在對應欄位。
 *
 * @param name         保管庫名稱
 * @param file         存放位置；還沒選時 {@code null}
 * @param password     主密碼（不會被修改或保留）
 * @param confirm      再次輸入的主密碼（不會被修改或保留）
 * @param acknowledged 是否勾選「我了解：忘記主密碼將無法復原資料」
 */
record CreateForm(String name, Path file, char[] password, char[] confirm, boolean acknowledged) {

    /** 表單欄位（錯誤顯示的位置，也是送出時焦點移動的順序）。 */
    enum Field { NAME, LOCATION, PASSWORD, CONFIRM, ACKNOWLEDGE }

    /**
     * @param field      出錯的欄位
     * @param messageKey {@code messages.properties} 的鍵
     */
    record Problem(Field field, String messageKey) { }

    /** 建立的檔案副檔名。 */
    static final String EXTENSION = ".kdbx";

    /**
     * @return 依欄位順序排列的錯誤；沒有錯誤時空清單
     */
    List<Problem> validate() {
        List<Problem> out = new ArrayList<>();
        if (name == null || name.strip().isEmpty()) {
            out.add(new Problem(Field.NAME, "create.error.name"));
        }
        if (file == null) {
            out.add(new Problem(Field.LOCATION, "create.error.location"));
        } else if (Files.exists(file)) {
            // 建立不覆寫既有的檔案：那可能是另一個保管庫，換掉就等於換了一個主密碼不同的保管庫
            out.add(new Problem(Field.LOCATION, "create.error.exists"));
        }
        if (password == null || password.length == 0) {
            out.add(new Problem(Field.PASSWORD, "create.error.empty"));
        } else if (!Arrays.equals(password, confirm)) {
            out.add(new Problem(Field.CONFIRM, "create.error.mismatch"));
        }
        if (!acknowledged) {
            out.add(new Problem(Field.ACKNOWLEDGE, "create.error.acknowledge"));
        }
        return List.copyOf(out);
    }

    /**
     * 另存新檔對話框選回來的檔名沒有副檔名時補上 {@code .kdbx}。
     *
     * @param chosen 對話框的結果
     * @return 一定以 {@code .kdbx} 結尾的路徑
     */
    static Path withExtension(Path chosen) {
        String fileName = chosen.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
            return chosen;
        }
        return chosen.resolveSibling(fileName + EXTENSION);
    }

    /**
     * 另存新檔對話框的預設檔名：保管庫名稱加 {@code .kdbx}。名稱本身已經以 {@code .kdbx} 結尾（不分大小寫）時先去掉，
     * 避免存成 {@code ….kdbx.kdbx}。
     *
     * @param vaultName 去掉前後空白的保管庫名稱
     * @return 預設檔名
     */
    static String suggestedFileName(String vaultName) {
        String base = vaultName;
        if (base.toLowerCase(Locale.ROOT).endsWith(EXTENSION) && base.length() > EXTENSION.length()) {
            base = base.substring(0, base.length() - EXTENSION.length());
        }
        return base + EXTENSION;
    }

    // record 預設的 toString 會列出每個欄位；主密碼欄位不出現在任何字串裡（CONVENTIONS §2）
    @Override
    public String toString() {
        return "CreateForm[name=" + name + ", file=" + file + "]";
    }
}
