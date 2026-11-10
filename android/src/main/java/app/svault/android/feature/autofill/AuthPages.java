package app.svault.android.feature.autofill;

import android.content.Context;
import android.content.IntentSender;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import java.util.Optional;

/**
 * 上鎖時的驗證項目要開哪個畫面（AC-10-2）：外殼的頁面框架載入 {@link AfAuthFragment}，上鎖時在同一個工作裡先開解鎖畫面、
 * 解鎖後回到這一頁，最後把結果交回系統。
 *
 * <p>正式程式是 {@link #SHELL}（T-84）：T-36 的 {@code ModuleHost.pageSender}。解鎖畫面按返回、「開啟其他檔案」或驗證頁
 * 顯示中被上鎖時，外殼讓頁面以 {@code RESULT_CANCELED} 結束，系統的驗證流程拿到取消、不填任何東西。
 */
interface AuthPages {

    /** 沒有驗證頁可開：上鎖時不提供建議（測試用；T-81 時外殼還沒有入口時的正式程式）。 */
    AuthPages NONE = (context, args, requestCode) -> Optional.empty();

    /** 外殼開的驗證頁（T-36 的入口；{@code FLAG_MUTABLE}、元件明確，D-168）。 */
    AuthPages SHELL = (context, args, requestCode) -> ModuleHost.of(context).pageSender(AutofillAndroidFeature.ID,
            R.string.af_auth_title, AfAuthFragment.class, args.toBundle(), requestCode);

    /**
     * 「搜尋保管庫」開的條目選擇頁（T-83，同一個入口，上鎖時一樣先解鎖）。requestCode 取負數：同一次請求的驗證頁與條目選擇頁
     * 不共用 {@code PendingIntent}。
     */
    AuthPages PICK = (context, args, requestCode) -> ModuleHost.of(context).pageSender(AutofillAndroidFeature.ID,
            R.string.af_pick_title, AfPickFragment.class, args.toBundle(), -1 - requestCode);

    /**
     * 每筆建議自己的驗證（T-110，D-197）：同一個驗證頁，參數帶條目代號（{@link AuthArgs#forEntry}）；解鎖中直接交回那一筆，
     * 上鎖時一樣先解鎖。requestCode 用 {@link EntryCodes#next}（不重複，和上面兩個不重疊）。
     */
    AuthPages FILL = (context, args, requestCode) -> ModuleHost.of(context).pageSender(AutofillAndroidFeature.ID,
            R.string.af_auth_title, AfAuthFragment.class, args.toBundle(), requestCode);

    /**
     * @param context     服務
     * @param args        給驗證頁的參數
     * @param requestCode 這次請求的代號（每個請求各自一個 {@code PendingIntent}，參數不會互相覆蓋）
     * @return 開啟驗證頁的 {@link IntentSender}（{@code FLAG_MUTABLE}：系統要附上 {@code EXTRA_CLIENT_STATE} 等）；
     *         沒有時為空
     */
    Optional<IntentSender> intentSender(Context context, AuthArgs args, int requestCode);
}
