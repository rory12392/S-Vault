package app.svault.android.feature.autofill;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.view.autofill.AutofillManager;
import androidx.fragment.app.Fragment;
import app.svault.android.R;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.module.ModulePreferences;

/**
 * 啟用引導的系統端（T-82；AC-10-1）：偵測 S-Vault 是不是目前的自動填入服務、開系統的服務選單、開引導頁、記下引導看過了。
 * 要顯示什麼由 {@link SetupState} 決定。畫面每次回到前景（{@code onResume}）都重新偵測：使用者從系統設定回來時就換狀態。
 */
final class AutofillSetup {

    /** 設定鍵 {@code modules.autofill.guideSeen}：引導頁顯示過（之後設定段落直接開系統設定）。 */
    static final String GUIDE_SEEN = "guideSeen";
    /** 設定鍵 {@code modules.autofill.enabled}：模組啟用（關閉時服務不提供建議，CONVENTIONS §11）。 */
    static final String ENABLED = "enabled";

    private AutofillSetup() {
    }

    /**
     * @param context 任何屬於這個 App 的 Context
     * @return 系統目前的狀態；{@code hasEnabledAutofillServices()} 只在目前的服務屬於本 App 時為 true
     */
    static SetupState.Service service(Context context) {
        AutofillManager manager = context.getSystemService(AutofillManager.class);
        if (manager == null) {
            return SetupState.Service.UNSUPPORTED;
        }
        boolean supported = manager.isAutofillSupported();
        return SetupState.Service.of(supported, supported && manager.hasEnabledAutofillServices());
    }

    /**
     * @param context 任何屬於這個 App 的 Context
     * @return 這次要顯示的狀態
     */
    static SetupState state(Context context) {
        return new SetupState(service(context), prefs(context).bool(GUIDE_SEEN).orElse(Boolean.FALSE));
    }

    /** @param context 任何屬於這個 App 的 Context；在主執行緒呼叫（{@code ModulePreferences}） */
    static void markGuideSeen(Context context) {
        prefs(context).put(GUIDE_SEEN, Boolean.TRUE);
    }

    /**
     * @param context 任何屬於這個 App 的 Context
     * @return 系統的自動填入服務選單（指定本 App，系統會詢問要不要改用 S-Vault）
     */
    static Intent systemSettingsIntent(Context context) {
        return new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
                .setData(Uri.parse("package:" + context.getPackageName()));
    }

    /**
     * 開系統的自動填入服務選單。選完回來由畫面的 {@code onResume} 重新偵測，不讀選單的結果（使用者也可能從別的地方改）。
     *
     * @param fragment 目前的畫面
     * @return 開得起來時為 {@code true}；有些裝置沒有這個選單（{@link ActivityNotFoundException}）時為 {@code false}
     */
    static boolean openSystemSettings(Fragment fragment) {
        try {
            fragment.startActivity(systemSettingsIntent(fragment.requireContext()));
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    /**
     * 開引導頁（外殼的頁面框架，T-36 的 {@code pageIntent}）。從設定頁開時一定是解鎖中；上鎖時外殼先開解鎖畫面。
     *
     * @param fragment 目前的畫面
     * @return 開得起來時為 {@code true}
     */
    static boolean openGuide(Fragment fragment) {
        return ModuleHost.of(fragment.requireContext())
                .pageIntent(AutofillAndroidFeature.ID, R.string.af_guide_title, AfGuideFragment.class, null)
                .map(intent -> {
                    fragment.startActivity(intent);
                    return true;
                })
                .orElse(false);
    }

    private static ModulePreferences prefs(Context context) {
        return ModuleHost.of(context).preferences(AutofillAndroidFeature.ID);
    }
}
