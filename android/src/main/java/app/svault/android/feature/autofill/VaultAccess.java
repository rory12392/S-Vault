package app.svault.android.feature.autofill;

import app.svault.core.module.UnlockedEntries;
import java.util.Optional;

/**
 * 自動填入怎麼拿到開啟中的保管庫（唯讀）。正式程式是外殼的 {@code ModuleHost.of(context)::unlockedEntries}（T-31）；
 * 留成介面讓 JVM 測試換成假資料。每次請求重新取，不存在欄位裡：拿到的物件綁在那次解鎖，上鎖後一律回傳空的結果。
 */
interface VaultAccess {

    /** 一律上鎖（測試用）。 */
    VaultAccess LOCKED = Optional::empty;

    /**
     * @return 這次解鎖的查詢；上鎖、或剛解鎖還在載入時為空（兩者都照上鎖處理）
     */
    Optional<UnlockedEntries> unlocked();
}
