package app.svault.core.session;

/**
 * 平台的系統剪貼簿（CONVENTIONS §2 的埠介面；T-19）。什麼時候清除由 {@link ClipboardGuard} 決定；「清除前要不要
 * 確認內容」因平台而異，由實作決定：
 * <ul>
 *   <li>desktop：剪貼簿內容仍是那個密碼才清；使用者已經複製了別的東西就不動（AC-05-2）。</li>
 *   <li>android：Android 10 起 App 在背景讀不到剪貼簿，無法比對內容，一律清除；只有讀得到、而且看得出是別的 App
 *       放的內容時才不動（AC-05-3，D-73）。</li>
 * </ul>
 * 只在平台的 UI 執行緒呼叫。實作不得把內容寫進日誌或例外訊息。
 */
public interface ClipboardPort {

    /**
     * 放進剪貼簿。
     *
     * @param value     內容（{@code null} 當成空字串）
     * @param sensitive 密碼等機密內容：平台支援時標成敏感（android 的剪貼簿預覽不顯示明文，AC-05-3）
     */
    void copy(String value, boolean sensitive);

    /**
     * 清除之前由 {@link #copy} 放進去的密碼（清除的條件見類別說明）。
     *
     * @param secret 當時複製的密碼（desktop 用來比對；android 不需要）
     */
    void clearSecret(String secret);
}
