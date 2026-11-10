package app.svault.android.shell.ext;

import android.content.Context;
import android.content.Intent;
import app.svault.core.module.FeatureModule;
import java.util.List;
import java.util.Optional;

/**
 * Android 的功能模組。在 {@code META-INF/services/app.svault.android.shell.ext.AndroidFeature} 註冊。
 *
 * <p>每個擴充點方法都由外殼包在 {@code ModuleGuard} 裡呼叫；預設不提供任何擴充。
 */
public interface AndroidFeature extends FeatureModule {

    /** @return 選單中的獨立頁面 */
    default List<MenuPage> menuPages() {
        return List.of();
    }

    /** @return 編輯畫面密碼欄旁的按鈕 */
    default List<PasswordFieldAction> passwordFieldActions() {
        return List.of();
    }

    /** @return 設定畫面中的模組段落 */
    default List<SettingsSection> settingsSections() {
        return List.of();
    }

    /** @return 解鎖畫面的替代解鎖方式 */
    /**
     * 解鎖後回到首頁時要自動開一次的頁面（例：自動填入的首次啟用引導，PRD US-10）。外殼在首頁建立時依模組順序問一次，
     * 第一個有頁面的才開；整段在模組的錯誤邊界裡。模組自己記錄「顯示過」，不要每次解鎖都開。
     *
     * @param context 首頁
     * @return 要開的頁面（{@code ModuleHost#pageIntent}）；沒有時為空
     */
    default Optional<Intent> afterUnlockPage(Context context) {
        return Optional.empty();
    }

    default List<UnlockProvider> unlockProviders() {
        return List.of();
    }

    /**
     * 停用通知（T-35）：{@code modules.<id>.enabled=false} 時，App 每次啟動都在背景執行緒（{@code svault-worker}，排在任何
     * 保管庫操作之前）呼叫一次，讓模組清掉只屬於這台裝置的資料（例如 BIO 刪除 Keystore 金鑰與密文）。啟用中的模組不會收到。
     *
     * <p>要能重複呼叫（已經清過時不做事）、不能耗時、不能碰畫面；由外殼包在 {@code ModuleGuard} 裡，丟例外只記錄。停用的模組
     * 除了這個方法之外完全不會被呼叫。預設不做事。
     *
     * @param context App 的 Context（{@code Application}）
     */
    default void onDisabled(Context context) {
    }
}
