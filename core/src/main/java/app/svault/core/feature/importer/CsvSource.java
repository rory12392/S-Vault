package app.svault.core.feature.importer;

import java.io.IOException;
import java.io.InputStream;

/**
 * 使用者選的 CSV（T-61、T-62；T-117 搬進 core）。desktop 是檔案路徑，android 是系統檔案選擇器給的文件，測試換成記憶體裡的
 * 位元組，所以 {@link ImportWizardModel} 不碰 {@code Path}、{@code Uri}，可以在 JVM 上測。
 *
 * <p>三個方法都只在背景執行緒呼叫（會做 I/O）。
 */
public interface CsvSource {

    /**
     * @return 檔案內容；呼叫端負責關閉
     * @throws IOException 讀不到（不存在、權限被收回、提供者出錯）
     */
    InputStream open() throws IOException;

    /** @return 顯示用的檔名（預覽的來源）；查不到時是空字串 */
    String name();

    /** @return 完成頁提示刪除時顯示的位置（AC-14-3）：查得出路徑時是路徑，否則是檔名 */
    String location();
}
