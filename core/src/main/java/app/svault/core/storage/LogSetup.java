package app.svault.core.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * 日誌設定：{@code java.util.logging}（兩平台都有，不加相依），寫到 {@code logs/svault<n>.log}，
 * 輪替最多 5 個、每個 1 MB（Architecture §2.2）。
 *
 * <p>安全底線：日誌不得含主密碼、條目內容或任何解密後的欄位（AC-15-1），呼叫端只記類型與非機密上下文。
 */
public final class LogSetup {

    /** S-Vault 所有 logger 的共同上層名稱。 */
    public static final String ROOT_LOGGER = "app.svault";

    static final int FILE_LIMIT_BYTES = 1024 * 1024;
    static final int FILE_COUNT = 5;

    private static final String FORMAT = "%1$tF %1$tT.%1$tL %4$s %3$s - %5$s%6$s%n";

    private static Handler installed;

    private LogSetup() {
    }

    /**
     * 把 {@code app.svault} 的日誌導向本機資料夾。重複呼叫時先關閉舊的檔案。
     *
     * @param store 本機資料夾
     * @return 日誌檔的檔名樣式（{@code %g} 是輪替序號）
     * @throws IOException 無法建立日誌資料夾或檔案時
     */
    public static synchronized Path install(LocalStore store) throws IOException {
        Files.createDirectories(store.logsDir());
        Path pattern = store.logsDir().resolve("svault%g.log");
        FileHandler handler = new FileHandler(pattern.toString(), FILE_LIMIT_BYTES, FILE_COUNT, true);
        handler.setEncoding(StandardCharsets.UTF_8.name());
        handler.setFormatter(new SimpleFormatter() {
            @Override
            public String format(java.util.logging.LogRecord r) {
                // 例外只記類別名稱：例外訊息可能夾帶解密後的內容（例如解析錯誤引用的欄位值）
                return String.format(Locale.ROOT, FORMAT, r.getMillis(), r.getSourceClassName(), r.getLoggerName(),
                        r.getLevel().getName(), formatMessage(r),
                        r.getThrown() == null ? "" : " [" + r.getThrown().getClass().getName() + "]");
            }
        });
        Logger root = Logger.getLogger(ROOT_LOGGER);
        if (installed != null) {
            root.removeHandler(installed);
            installed.close();
        }
        root.addHandler(handler);
        root.setLevel(Level.INFO);
        installed = handler;
        return pattern;
    }

    /**
     * 關閉日誌檔（測試結束、程式結束前）。
     */
    public static synchronized void uninstall() {
        if (installed != null) {
            Logger.getLogger(ROOT_LOGGER).removeHandler(installed);
            installed.close();
            installed = null;
        }
    }
}
