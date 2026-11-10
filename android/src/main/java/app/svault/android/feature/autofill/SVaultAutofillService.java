package app.svault.android.feature.autofill;

import android.app.assist.AssistStructure;
import android.content.Context;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.view.autofill.AutofillId;
import androidx.annotation.NonNull;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModulePreferences;
import java.util.List;
import java.util.Optional;

/**
 * S-Vault 的系統自動填入服務（AF，US-10；manifest 宣告由 T-22 建立）。這裡只做轉接：系統的節點轉成 {@link AfNode}、
 * 辨識與比對交給 {@link AutofillRequests}（純 Java，JVM 測試）、結果轉成 {@link FillResponse}，整段在
 * {@link ModuleGuard} 裡，出錯時回傳 {@code null}（不提供建議）。
 *
 * <p>保管庫是外殼的唯讀查詢（{@code ModuleHost.unlockedEntries()}，T-31），每次請求重新取。上鎖時回傳需要驗證的項目
 * （{@link FillResponses#locked}，T-81），選了之後由外殼開驗證頁、先解鎖再重新比對（{@link #authPages}，T-84）。
 * 沒有符合時提供「搜尋保管庫」（{@link FillResponses#searchOnly}），選了之後開條目選擇頁、挑了不符的條目先警告（T-83）。
 */
public final class SVaultAutofillService extends AutofillService {

    // 模組錯誤只由 ModuleGuard 記錄：服務沒有可以顯示錯誤狀態的畫面
    private final ModuleGuard guard = new ModuleGuard((moduleId, action, error) -> { });
    // 上鎖時的驗證頁：外殼開、先解鎖再回到頁面（T-36、T-84）
    private final AuthPages authPages = AuthPages.SHELL;
    // 沒有符合時「搜尋保管庫」的條目選擇頁（T-83）
    private final AuthPages pickPages = AuthPages.PICK;

    @Override
    public void onFillRequest(@NonNull FillRequest request, @NonNull CancellationSignal cancellationSignal,
            @NonNull FillCallback callback) {
        Optional<FillResponse> response = guard.call(AutofillAndroidFeature.ID, "fillResponse", () -> {
            List<FillContext> contexts = request.getFillContexts();
            AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
            String packageName = structure.getActivityComponent().getPackageName();
            VaultAccess vault = ModuleHost.of(this)::unlockedEntries;
            Optional<FillPlan<AutofillId>> plan = new AutofillRequests(guard, getPackageName(), new PackageSigners(this),
                    AutofillAndroidFeature.pins(this))
                    .handle(packageName, ViewNodeAdapter.roots(structure),
                            () -> AutofillAndroidFeature.enabled(this), vault);
            return plan.map(p -> respond(p, packageName, request.getId())).orElse(null);
        });
        // null＝這次沒有建議（不是錯誤），系統不顯示任何項目
        callback.onSuccess(response.orElse(null));
    }

    private FillResponse respond(FillPlan<AutofillId> plan, String packageName, int requestId) {
        AuthArgs args = new AuthArgs(packageName, plan.fields());
        return switch (plan.outcome()) {
            case LOCKED -> authPages.intentSender(this, args, requestId)
                    .map(sender -> FillResponses.locked(this, plan, sender))
                    .orElse(null);
            case SEARCH_ONLY -> pickPages.intentSender(this, args, requestId)
                    .map(sender -> FillResponses.searchOnly(this, plan, sender))
                    .orElse(null);
            default -> FillResponses.build(this, plan, entrySenders(this, args));
        };
    }

    /**
     * @param context 服務或驗證頁
     * @param args    這次請求的參數
     * @return 每筆建議的驗證頁（T-110，D-197：建議不帶值，選了之後確認仍解鎖才交回）
     */
    static FillResponses.EntrySenders entrySenders(Context context, AuthArgs args) {
        // 每筆各自一個不重複的代號（EntryCodes）：撞號時舊建議會指向別的請求的參數
        ModulePreferences prefs = ModuleHost.of(context).preferences(AutofillAndroidFeature.ID);
        return (entry, index) -> AuthPages.FILL.intentSender(context, args.forEntry(entry.uuid().toString()),
                EntryCodes.next(prefs));
    }

    @Override
    public void onSaveRequest(@NonNull SaveRequest request, @NonNull SaveCallback callback) {
        // 本版不處理「儲存新密碼」；autofill_service.xml 也沒有宣告儲存，正常不會被呼叫
        callback.onSuccess();
    }
}
