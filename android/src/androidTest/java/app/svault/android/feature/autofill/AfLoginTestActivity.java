package app.svault.android.feature.autofill;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;

/**
 * 實測自動填入用的登入表單（T-110：建議顯示後 S-Vault 上鎖，舊建議還能不能填）。只在測試 APK 裡，套件名稱是
 * {@code app.svault.android.test}；測試保管庫放一筆網址 {@code androidapp://app.svault.android.test} 的假條目。
 * 用 {@code adb shell am start -n app.svault.android.test/app.svault.android.feature.autofill.AfLoginTestActivity} 開。
 */
public class AfLoginTestActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 240, 48, 48);
        root.addView(field("af_test_username", "username", InputType.TYPE_CLASS_TEXT));
        root.addView(field("af_test_password", "password",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        setContentView(root);
    }

    private EditText field(String tag, String hint, int type) {
        EditText e = new EditText(this);
        e.setId(View.generateViewId());
        e.setTag(tag);
        e.setHint(hint);
        e.setContentDescription(tag);
        e.setInputType(type);
        e.setAutofillHints(hint);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        return e;
    }
}
