package app.svault.android.feature.autofill;

import java.util.List;

/**
 * 自動填入要看的畫面節點（{@code AssistStructure.ViewNode} 的抽象）。欄位辨識與比對寫成純邏輯、在 JVM 上測試，
 * 正式程式由 {@link ViewNodeAdapter} 轉接系統給的節點。
 *
 * <p>只取辨識欄位需要的屬性，不取欄位目前的文字（{@code getText()}／{@code getAutofillValue()}）：
 * 請求來源畫面上的內容不經過本模組。
 *
 * @param <I> 欄位代號的型別（正式程式是 {@code AutofillId}，測試用字串）
 */
interface AfNode<I> {

    /** @return 欄位代號；沒有時為 {@code null}（無法填入，略過） */
    I autofillId();

    /** @return {@code android:autofillHints}（含網頁的 {@code autocomplete}）；沒有時為空清單 */
    List<String> autofillHints();

    /** @return {@code android:inputType}；沒有時為 0 */
    int inputType();

    /** @return {@code View.AUTOFILL_TYPE_*}；不可填入時為 {@code AUTOFILL_TYPE_NONE}（0） */
    int autofillType();

    /** @return 資源 id 的名稱（{@code R.id.} 後面那段）；沒有時為 {@code null} */
    String idEntry();

    /** @return 提示文字（{@code android:hint}）；沒有時為 {@code null} */
    String hint();

    /** @return 網頁 {@code <input>} 的 {@code type} 屬性；不是網頁欄位時為 {@code null} */
    String htmlInputType();

    /** @return 網頁欄位的 {@code name} 或 {@code id} 屬性（以空白相接）；沒有時為 {@code null} */
    String htmlName();

    /** @return 這個節點的網頁網域（瀏覽器與 WebView 才有）；沒有時為 {@code null} */
    String webDomain();

    /** @return 這個節點網頁的 scheme（{@code http}／{@code https}）；不知道時為 {@code null}（C-5、L-3） */
    default String webScheme() {
        return null;
    }

    /** @return 是否看得見（{@code View.VISIBLE}） */
    boolean visible();

    /** @return 子節點（依畫面順序） */
    List<? extends AfNode<I>> children();
}
