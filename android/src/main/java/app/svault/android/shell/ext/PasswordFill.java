package app.svault.android.shell.ext;

import androidx.annotation.Nullable;

/**
 * 編輯畫面交給 {@link PasswordFieldAction} 的回填（T-37）：模組開自己的畫面（例如 GEN 的底部 sheet），等使用者按
 * 「使用這個密碼」後再呼叫 {@link #fill}，外殼把值填進密碼欄。由外殼實作，模組不要自己實作。
 *
 * <p><b>只在這個編輯畫面還在、可以編輯時有效</b>：畫面重建（旋轉、換主題）、離開（儲存、取消、返回）、被上鎖之後一律
 * 不回填，也不會在之後的畫面填入。只在主執行緒使用。拿到的物件不要存到靜態欄位或畫面結束之後；模組的 sheet 在重建時
 * 不會被還原（外殼換成空白佔位），所以存在 sheet 自己的欄位就夠。
 */
public interface PasswordFill {

    /**
     * 把值填進密碼欄（取代原本的內容）。<b>不論有沒有填入，回傳前都會把 {@code value} 清零</b>，呼叫端不要再用它，也不用
     * 再清；值不會被記錄或保留。
     *
     * @param value 要填入的密碼；{@code null} 時不做事
     * @return 有填入時為 {@code true}；畫面已重建、已離開、已上鎖、正在儲存或內容還沒載入時為 {@code false}
     * @throws IllegalStateException 不在主執行緒（值一樣清零）
     */
    boolean fill(@Nullable char[] value);

    /**
     * @return 現在呼叫 {@link #fill} 會填入（模組可以用它決定要不要繼續顯示自己的畫面）；只在主執行緒呼叫
     */
    boolean isActive();
}
