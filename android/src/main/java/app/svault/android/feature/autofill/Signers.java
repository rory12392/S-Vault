package app.svault.android.feature.autofill;

import java.util.Set;

/**
 * 查一個已安裝 App 的簽署憑證（C-1）。正式實作是 {@link PackageSigners}（{@code PackageManager}）；JVM 測試傳入固定的值。
 */
@FunctionalInterface
interface Signers {

    /** 什麼都查不到（測試與還沒有 Context 的地方用）。 */
    Signers NONE = packageName -> Set.of();

    /**
     * @param packageName 套件名稱
     * @return 目前簽署憑證的 SHA-256（大寫十六進位、沒有冒號）；沒有安裝或查不到時是空集合
     */
    Set<String> of(String packageName);
}
