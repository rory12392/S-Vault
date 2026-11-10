package app.svault.android.feature.autofill;

import app.svault.android.feature.autofill.FillPlan.Outcome;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryQuery;
import app.svault.core.vault.EntryView;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 決定一次請求要提供什麼（AC-10-3、AC-10-5）。比對規則在 core 的
 * {@code EntryQuery.autofill}／{@code UrlMatch}（T-25，經 {@code UnlockedEntries.autofill}）；這裡只決定什麼時候能比、
 * 比完怎麼用。依序：
 * <ol>
 * <li>停用、沒有來源套件、S-Vault 自己的畫面、沒有登入欄位 → {@link Outcome#NONE}。</li>
 * <li>瀏覽器但沒有網域 → {@link Outcome#SEARCH_ONLY}，不比對；不是瀏覽器時不看網域（{@link Browsers#trustedDomain}）。</li>
 * <li>上鎖、還在載入、或拿到的查詢已失效 → {@link Outcome#LOCKED}（不比對）。</li>
 * <li>比對；帳號與密碼都空白的條目略過（沒東西可填）；一筆都沒有 → {@link Outcome#SEARCH_ONLY}。</li>
 * </ol>
 */
final class FillPlanner {

    private final String ownPackage;
    private final Signers signers;
    private final AppSignerPins pins;

    /**
     * @param ownPackage S-Vault 自己的套件名稱（自己的畫面不提供建議：解鎖畫面的主密碼欄不該出現保管庫的帳密）
     * @param signers    查瀏覽器的簽署憑證（C-1：網域只採信驗證過的瀏覽器）
     */
    FillPlanner(String ownPackage, Signers signers) {
        this(ownPackage, signers, AppSignerPins.OFF);
    }

    /**
     * @param ownPackage S-Vault 自己的套件名稱
     * @param signers    查簽署憑證
     * @param pins       一般 App 的憑證紀錄（I-5：換了憑證就不自動交出）
     */
    FillPlanner(String ownPackage, Signers signers, AppSignerPins pins) {
        this.ownPackage = ownPackage;
        this.signers = signers;
        this.pins = pins;
    }

    /**
     * @param fields      畫面裡的登入欄位
     * @param packageName 請求來源的套件名稱
     * @param enabled     模組是否啟用（{@code modules.autofill.enabled}）
     * @param vault       保管庫
     * @param <I>         欄位代號的型別
     * @return 要提供的內容
     */
    <I> FillPlan<I> plan(FieldScan<I> fields, String packageName, boolean enabled, VaultAccess vault) {
        if (!enabled || packageName == null || packageName.equals(ownPackage) || !fields.hasFields()) {
            return FillPlan.of(Outcome.NONE, fields);
        }
        // 非瀏覽器自稱的網域不採信（H-1），照一般 App 用套件名稱比對
        String domain = Browsers.trustedDomain(packageName, fields.webDomain(), signers);
        if (domain == null && Browsers.isBrowser(packageName)) {
            return FillPlan.of(Outcome.SEARCH_ONLY, fields);
        }
        Optional<UnlockedEntries> query = vault.unlocked();
        // 上鎖後先前拿到的物件 isOpen()=false、查詢為空（T-31）：照上鎖處理，不要變成「沒有符合」
        if (query.isEmpty() || !query.get().isOpen()) {
            return FillPlan.of(Outcome.LOCKED, fields);
        }
        List<EntryView> usable = new ArrayList<>();
        for (EntryView e : query.get().autofill(packageName, fields.requestDomain(domain))) {
            if (hasText(e.username()) || hasText(e.password())) {
                usable.add(e);
            }
        }
        if (usable.isEmpty()) {
            return FillPlan.of(Outcome.SEARCH_ONLY, fields);
        }
        // 一般 App（照套件名稱比對）：第一次記下憑證，之後換了就只給「搜尋保管庫」（I-5）
        if (domain == null && !pins.trusted(packageName, signers.of(packageName))) {
            return FillPlan.of(Outcome.SEARCH_ONLY, fields);
        }
        // EntryQuery 已限制 10 筆；再截一次，不讓核心的上限改了就多送
        int limit = Math.min(usable.size(), EntryQuery.AUTOFILL_LIMIT);
        return new FillPlan<>(Outcome.ENTRIES, fields, usable.subList(0, limit));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }
}
