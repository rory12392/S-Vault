package app.svault.android.feature.autofill;

import app.svault.core.module.ModuleGuard;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code onFillRequest} 不含 Android 型別的部分：走訪、比對、記錄，整段在 {@link ModuleGuard} 裡（CONVENTIONS §11）。
 * 服務只負責把系統的節點轉成 {@link AfNode}、把結果轉成 {@code FillResponse}。
 */
final class AutofillRequests {

    private static final Logger LOG = Logger.getLogger(AutofillRequests.class.getName());

    private final ModuleGuard guard;
    private final FillPlanner planner;

    /**
     * @param guard      錯誤邊界
     * @param ownPackage S-Vault 自己的套件名稱
     * @param signers    查瀏覽器的簽署憑證（C-1）
     */
    AutofillRequests(ModuleGuard guard, String ownPackage, Signers signers) {
        this(guard, ownPackage, signers, AppSignerPins.OFF);
    }

    /**
     * @param guard      錯誤邊界
     * @param ownPackage S-Vault 自己的套件名稱
     * @param signers    查簽署憑證（C-1）
     * @param pins       一般 App 的憑證紀錄（I-5）
     */
    AutofillRequests(ModuleGuard guard, String ownPackage, Signers signers, AppSignerPins pins) {
        this.guard = guard;
        this.planner = new FillPlanner(ownPackage, signers, pins);
    }

    /**
     * @param packageName 請求來源的套件名稱
     * @param roots       各視窗的根節點
     * @param enabled     模組是否啟用（在邊界內讀，讀設定失敗也不讓服務崩潰）
     * @param vault       保管庫
     * @param <I>         欄位代號的型別
     * @return 處理結果；出錯時為空（服務回傳 {@code null}，不提供建議）
     */
    <I> Optional<FillPlan<I>> handle(String packageName, List<? extends AfNode<I>> roots, BooleanSupplier enabled,
            VaultAccess vault) {
        return guard.call(AutofillAndroidFeature.ID, "fillRequest", () -> {
            if (!enabled.getAsBoolean()) {
                return FillPlan.<I>of(FillPlan.Outcome.NONE, new FieldScan<>(List.of(), List.of(), null));
            }
            return planAndLog(FieldScanner.scan(roots), packageName, vault);
        });
    }

    /**
     * 驗證流程（AC-10-2）：解鎖畫面回來、這次解鎖的內容載入好之後，用驗證項目帶來的欄位、套件與網域重新比對。
     * 規則和 {@link #handle} 相同（不符的一樣不給帳密）；動作名稱 {@code authResult}。
     *
     * @param fields      要求驗證時的登入欄位與網域
     * @param packageName 請求來源的套件名稱
     * @param enabled     模組是否啟用
     * @param vault       保管庫
     * @param <I>         欄位代號的型別
     * @return 處理結果；出錯時為空
     */
    <I> Optional<FillPlan<I>> afterUnlock(FieldScan<I> fields, String packageName, BooleanSupplier enabled,
            VaultAccess vault) {
        return guard.call(AutofillAndroidFeature.ID, "authResult", () -> {
            if (!enabled.getAsBoolean()) {
                return FillPlan.of(FillPlan.Outcome.NONE, fields);
            }
            return planAndLog(fields, packageName, vault);
        });
    }

    private <I> FillPlan<I> planAndLog(FieldScan<I> fields, String packageName, VaultAccess vault) {
        FillPlan<I> plan = planner.plan(fields, packageName, true, vault);
        if (plan.outcome() != FillPlan.Outcome.NONE) {
            // 只記來源套件與是否有符合：網域、欄位與條目都不記
            LOG.log(Level.INFO, "自動填入請求：package={0} matched={1}",
                    new Object[] {packageName, plan.matched()});
        }
        return plan;
    }
}
