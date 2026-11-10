package app.svault.android.platform;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 檔案選擇器（Storage Access Framework）給的文件 Uri 的最小存取介面。正式程式是 {@link ContentResolverDocuments}；
 * JVM 單元測試換成記憶體中的假文件，模擬權限被撤銷、離線、檔案被刪除與寫到一半中斷（AC-06-4、AC-09-5）。
 *
 * <p>Uri 一律以字串傳遞：{@code recent.json} 記的就是字串，JVM 測試也不需要 {@code android.net.Uri}。
 * 方法是同步 I/O，呼叫端放在背景執行緒。
 */
public interface DocumentAccess {

    /**
     * @param uri 文件 Uri
     * @return 文件的顯示名稱；文件不存在時為 {@code null}
     * @throws SecurityException 沒有存取權限
     * @throws IOException       無法查詢（例如提供者暫時無法使用）
     */
    String queryDisplayName(String uri) throws IOException;

    /**
     * @param uri 文件 Uri
     * @return 讀取串流
     * @throws SecurityException            沒有存取權限
     * @throws java.io.FileNotFoundException 文件不存在，或提供者拿不到內容（離線且沒有快取）
     * @throws IOException                  其他讀取失敗
     */
    InputStream openRead(String uri) throws IOException;

    /**
     * 以 {@code "wt"}（寫入＋截斷）模式開啟，整檔覆寫。
     *
     * @param uri 文件 Uri
     * @return 寫入串流；關閉時才算寫完
     * @throws SecurityException            沒有寫入權限
     * @throws java.io.FileNotFoundException 文件不存在
     * @throws IOException                  其他寫入失敗
     */
    OutputStream openOverwrite(String uri) throws IOException;

    /**
     * 看得出文件在 Google 雲端硬碟的垃圾桶裡（T-106，D-191）。Drive 的文件移到垃圾桶後名稱仍查得到，但讀不到內容、
     * 也拿掉「可移動」「可改名」的旗標、「可寫」仍在（2026-11-10 Pixel 9 Pro 實測）；不能確定時一律 {@code false}，照原本的
     * 離線處理。這是依旗標推測，不是 Drive 給的垃圾桶狀態；寫入前檢查與實際寫入之間移進垃圾桶的情況擋不到。
     *
     * @param uri 文件 Uri
     * @return 看得出在垃圾桶裡
     */
    default boolean looksTrashed(String uri) {
        return false;
    }

    /**
     * @param uri 文件 Uri
     * @return App 仍持有這個 Uri 的長期讀寫權限（重新啟動後不需重新選檔，AC-06-2）
     */
    boolean hasPersistedPermission(String uri);

    /**
     * 取得長期讀寫權限；使用者剛從檔案選擇器選了檔案時呼叫。
     *
     * @param uri 文件 Uri
     * @throws SecurityException 選擇器沒有給可保留的權限
     */
    void takePersistedPermission(String uri);

    /**
     * 放棄長期權限（從最近開啟清單移除時呼叫；系統對每個 App 可保留的數量有上限）。
     *
     * @param uri 文件 Uri
     */
    void releasePersistedPermission(String uri);

    /**
     * 刪除文件。只用在「建立保管庫」寫第一版失敗時，收掉另存新檔選擇器（{@code ACTION_CREATE_DOCUMENT}）
     * 剛建立的空檔案，免得 Drive 上留下一個打不開的 0 位元組 {@code .kdbx}。
     *
     * @param uri 文件 Uri
     * @return 刪除成功；提供者不支援刪除、沒有權限或文件已不存在時為 {@code false}（不丟例外）
     */
    boolean deleteDocument(String uri);
}
