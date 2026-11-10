package app.svault.android.feature.autofill;

import android.content.Context;
import android.content.Intent;
import app.svault.android.R;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.android.shell.ext.SettingsSection;
import app.svault.core.module.ModulePreferences;
import java.util.List;
import java.util.Optional;

/**
 * 自動填入（AF，US-10）的 Android 模組類別：註冊設定頁的「自動填入」段落（{@link AfSettingsFragment}，T-82：目前狀態、
 * 啟用引導與系統設定、模組啟用開關）。系統的自動填入服務是另一個類別 {@link SVaultAutofillService}（manifest 宣告）。
 * 文字資源放 {@code res/values/af_strings.xml}。
 */
public final class AutofillAndroidFeature implements AndroidFeature {

    /** 模組代號（設定鍵 {@code modules.autofill.*}）。 */
    public static final String ID = "autofill";

    /**
     * @param context 任何屬於這個 App 的 Context（服務、驗證頁）
     * @return {@code modules.autofill.enabled}；沒設過時用預設值
     */
    static boolean enabled(Context context) {
        return ModuleHost.of(context).preferences(ID).bool(AutofillSetup.ENABLED)
                .orElse(new AutofillAndroidFeature().enabledByDefault());
    }

    /**
     * @param context 任何屬於這個 App 的 Context（在 UI 執行緒用：寫設定要在 UI 執行緒）
     * @return 一般 App 的簽署憑證紀錄（I-5），存在 {@code modules.autofill.pins.*}
     */
    static AppSignerPins pins(Context context) {
        ModulePreferences prefs = ModuleHost.of(context).preferences(ID);
        return new AppSignerPins(new AppSignerPins.Store() {
            @Override
            public Optional<String> get(String key) {
                return prefs.string(key);
            }

            @Override
            public void put(String key, String value) {
                prefs.put(key, value);
            }
        });
    }

    @Override
    public Optional<Intent> afterUnlockPage(Context context) {
        if (!AutofillSetup.state(context).offerAfterUnlock(enabled(context))) {
            return Optional.empty();
        }
        return ModuleHost.of(context).pageIntent(ID, R.string.af_guide_title, AfGuideFragment.class, null);
    }

    @Override
    public List<SettingsSection> settingsSections() {
        return List.of(new SettingsSection(R.string.af_settings_title, AfSettingsFragment.class));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        // FeatureModule 是 core 的介面，這裡拿不到 Context 讀字串資源；只在模組的擴充點整個失敗時當段落標題
        return "自動填入";
    }
}
