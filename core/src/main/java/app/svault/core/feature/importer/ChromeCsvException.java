package app.svault.core.feature.importer;

/**
 * 整個檔案無法當成 Chrome 密碼 CSV 匯入（個別的列格式錯誤不算，那些列以 {@link SkippedLine} 回報）。
 * 訊息只描述原因，不含檔案內容。
 */
public final class ChromeCsvException extends Exception {

    private static final long serialVersionUID = 1L;

    /** 無法匯入的原因；平台外殼依此顯示繁體中文訊息。 */
    public enum Kind {
        /** 檔案是空的，或只有空白行（沒有標題列）。 */
        EMPTY,
        /** 標題列缺少 {@code url}、{@code username} 或 {@code password}，或標題列本身格式錯誤。 */
        MISSING_COLUMNS,
        /** 不是 UTF-8 文字。 */
        NOT_UTF8,
        /** 超過 {@link ChromeCsvParser#MAX_BYTES}。 */
        TOO_LARGE,
    }

    private final Kind kind;

    /**
     * @param kind    原因
     * @param message 非機密的說明（開發者看的）
     */
    public ChromeCsvException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    /**
     * @return 原因
     */
    public Kind kind() {
        return kind;
    }
}
