package app.svault.core.feature.importer;

import app.svault.core.vault.EntryDraft;

/**
 * Chrome 密碼 CSV 的一列（已解析，值和原檔一致，不去除前後空白）。檔案沒有的欄位（例如舊版匯出沒有
 * {@code note}）為空字串。
 *
 * <p>和 {@link app.svault.core.vault.EntryView} 一樣，Java 字串無法保證從記憶體清除；匯入完成或取消時由畫面
 * 丟棄整個解析結果的參照。
 *
 * @param line     這一列在檔案中開始的行號（標題列是第 1 行）
 * @param name     {@code name} 欄
 * @param url      {@code url} 欄
 * @param username {@code username} 欄
 * @param password {@code password} 欄
 * @param note     {@code note} 欄
 */
public record ChromeCsvRow(int line, String name, String url, String username, String password, String note) {

    /** 名稱、網址、帳號都空白時的條目名稱（{@link app.svault.core.vault.VaultEditor} 不接受空白名稱）。 */
    public static final String UNTITLED = "（未命名）";

    /**
     * {@code null} 一律當成空字串。
     */
    public ChromeCsvRow {
        name = name == null ? "" : name;
        url = url == null ? "" : url;
        username = username == null ? "" : username;
        password = password == null ? "" : password;
        note = note == null ? "" : note;
    }

    /**
     * 轉成條目草稿。名稱空白時（Chrome 的 {@code name} 可能是空的）依序改用網址的主機名稱、網址本身、帳號，
     * 都沒有時用 {@link #UNTITLED}，所以名稱一定不是空白。
     *
     * @return 草稿；帳號、密碼、網址、備註和原檔一致
     */
    public EntryDraft toDraft() {
        return new EntryDraft(title(), username, password, url, note);
    }

    /** 全部空白（{@link SkippedLine.Reason#EMPTY_ENTRY}）。 */
    boolean isEmpty() {
        return (name + url + username + password + note).trim().isEmpty();
    }

    // 用 trim() 而不是 Java 11 的 isBlank()／strip()：core 只能用 Android API 29 有的 API（CONVENTIONS §2）
    private String title() {
        if (!name.trim().isEmpty()) {
            return name;
        }
        String host = HostNames.display(url);
        if (!host.isEmpty()) {
            return host;
        }
        if (!url.trim().isEmpty()) {
            return url.trim();
        }
        if (!username.trim().isEmpty()) {
            return username.trim();
        }
        return UNTITLED;
    }

    /**
     * 只輸出行號：帳號、密碼等內容不能因為被印出或寫進日誌而外洩（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "ChromeCsvRow[line=" + line + "]";
    }
}
