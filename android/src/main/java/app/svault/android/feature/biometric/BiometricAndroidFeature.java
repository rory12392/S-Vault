package app.svault.android.feature.biometric;

import android.content.Context;
import app.svault.android.R;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.SettingsSection;
import app.svault.android.shell.ext.UnlockProvider;
import java.util.List;

/**
 * 生物辨識（BIO，US-11）的 Android 模組類別：註冊解鎖畫面的「使用生物辨識解鎖」（{@link BioUnlockProvider}）與設定頁的
 * 「生物辨識解鎖」段落（{@link BioSettingsFragment}）（T-71）。外殼把每次呼叫都包在
 * {@code ModuleGuard} 裡；{@code modules.biometric.enabled=false} 時兩者都不出現，並在 {@link #onDisabled} 刪除金鑰與密文
 * （T-72）。
 */
public final class BiometricAndroidFeature implements AndroidFeature {

    /** 模組代號（設定鍵 {@code modules.biometric.*}）。 */
    public static final String ID = "biometric";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        // FeatureModule 是 core 的介面，這裡拿不到 Context 讀字串資源；只在模組的擴充點整個失敗時當段落標題
        return "生物辨識解鎖";
    }

    @Override
    public List<UnlockProvider> unlockProviders() {
        return List.of(new BioUnlockProvider(new KeystoreBioKeys()));
    }

    @Override
    public List<SettingsSection> settingsSections() {
        return List.of(new SettingsSection(R.string.bio_settings_title, BioSettingsFragment.class));
    }

    /**
     * 模組被停用（{@code modules.biometric.enabled=false}）時，App 每次啟動在背景執行緒呼叫一次（T-35）：刪除這台裝置上
     * 所有保管庫的金鑰與密文（規格 §1，T-72）。外殼包在錯誤邊界裡，例外只記錄。
     */
    @Override
    public void onDisabled(Context context) {
        clearAll(BioVault.of(context));
    }

    /**
     * @param vault 金鑰與密文；已經清過時不做事（每次啟動都會呼叫）
     * @throws IllegalStateException 金鑰庫錯誤（密文仍已刪除；交給外殼的錯誤邊界記錄）
     */
    static void clearAll(BioVault vault) {
        try {
            vault.disableAll();
        } catch (BioException e) {
            throw new IllegalStateException("刪除生物辨識金鑰失敗", e);
        }
    }
}
