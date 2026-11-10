package app.svault.android.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.TextUtils;
import android.text.method.PasswordTransformationMethod;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.textfield.TextInputEditText;
import java.util.Arrays;
import java.util.List;

/**
 * 密碼欄（主密碼、條目密碼）：遮罩時不把明文交給無障礙服務（T-19）。
 *
 * <p>Android 8 拿掉「說出密碼」的開關後，系統設定 {@code speak_password} 預設為 1，框架就把密碼欄的明文放進無障礙
 * 節點與文字變更事件（只多標一個 {@code isPassword}），由 TalkBack 自己決定唸不唸。結果任何拿到無障礙權限的 App、
 * 以及 {@code uiautomator dump} 都讀得到主密碼（T-19 在 Pixel 9 Pro／Android 17 實測：節點 {@code text=probe1234}）。
 * 這裡改回「遮罩時只給遮罩字元」：節點文字與事件文字換成同樣長度的「•」（TalkBack 仍可說出字數）；使用者按「顯示」
 * 切成明文時，畫面上本來就看得到，照常提供（TalkBack 使用者才能確認輸入的內容）。
 *
 * <p>另外兩件事也放在這裡，每個密碼欄自動套用（T-20）：
 * <ul>
 *   <li><b>不交給其他自動填入服務</b>：一律 {@code importantForAutofill=no}，Google 等密碼管理器不會提議把主密碼
 *       「儲存」到它們那裡（Material 的 TextInputLayout 預設是 yes，所以連設定的方法也鎖住）。</li>
 *   <li><b>顯示明文的期間防截圖</b>：解鎖與建立保管庫畫面平常沒有 {@code FLAG_SECURE}（D-58，不顯示保管庫內容、
 *       要能截圖驗收），但按「顯示」後畫面上就是主密碼；這段期間替視窗加上 {@code FLAG_SECURE}，切回遮罩時只拿掉自己
 *       加的（條目畫面本來就有，不動）。</li>
 * </ul>
 *
 * <p>版面裡 {@code textPassword} 的欄位一律用這個類別（{@code SecretFieldLayoutTest} 檢查）。
 */
public class SecretEditText extends TextInputEditText {

    /** 遮罩字元（同 {@link PasswordTransformationMethod} 顯示的點）。 */
    static final char DOT = '•';

    /** 視窗的 {@code FLAG_SECURE} 是這個欄位顯示明文時加的（切回遮罩時才由它拿掉）。 */
    private boolean secureAddedHere;
    /** 上一次畫的時候是不是明文。 */
    private boolean shownLast;
    private final ViewTreeObserver.OnPreDrawListener revealWatch = this::checkReveal;

    public SecretEditText(@NonNull Context context) {
        super(context);
        super.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
    }

    public SecretEditText(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        super.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
    }

    public SecretEditText(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        super.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
    }

    /** @return 目前是遮罩顯示（還沒按「顯示」） */
    public boolean isMasked() {
        return getTransformationMethod() instanceof PasswordTransformationMethod;
    }

    /** 密碼欄一律不交給自動填入服務：忽略版面或函式庫要改成其他值。 */
    @Override
    public void setImportantForAutofill(int mode) {
        super.setImportantForAutofill(IMPORTANT_FOR_AUTOFILL_NO);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnPreDrawListener(revealWatch);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnPreDrawListener(revealWatch);
        super.onDetachedFromWindow();
    }

    /**
     * Material 的「顯示密碼」按鈕換掉的是轉換方法（{@code setTransformationMethod} 是 final，不能覆寫），所以在每一幀
     * 畫之前比對。剛切成明文時先加 {@code FLAG_SECURE} 並取消這一幀：下一次繪製前視窗會先套用新旗標，明文不會在
     * 沒有防護的那一幀出現。
     *
     * @return {@code false}＝取消這一幀
     */
    private boolean checkReveal() {
        boolean shown = !isMasked();
        if (shown == shownLast) {
            return true;
        }
        shownLast = shown;
        return !guardRevealedText();
    }

    /** @return 這次替視窗加上了 {@code FLAG_SECURE} */
    private boolean guardRevealedText() {
        Window window = window();
        if (window == null) {
            return false;
        }
        boolean secure = (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
        if (!isMasked() && !secure) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
            secureAddedHere = true;
            return true;
        } else if (isMasked() && secureAddedHere) {
            secureAddedHere = false;
            // 同一個畫面的另一個欄位（建立保管庫的確認欄）還顯示著明文時不拿掉，改由它切回遮罩時拿掉
            SecretEditText other = revealedOther(getRootView());
            if (other == null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
            } else {
                other.secureAddedHere = true;
            }
        }
        return false;
    }

    /** @return 同一個畫面上正顯示明文的另一個密碼欄；沒有時 {@code null} */
    @Nullable
    private SecretEditText revealedOther(View view) {
        if (view instanceof SecretEditText field && field != this && !field.isMasked()) {
            return field;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                SecretEditText found = revealedOther(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Nullable
    private Window window() {
        Context c = getContext();
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity activity) {
                return activity.getWindow();
            }
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(@NonNull AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (isMasked()) {
            info.setPassword(true);
            // 空白時框架給的是提示文字（「主密碼」），照常保留
            CharSequence text = getText();
            if (text != null && text.length() > 0) {
                info.setText(dots(text.length()));
            }
        }
    }

    @Override
    public void onInitializeAccessibilityEvent(@NonNull AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (isMasked() && event.getBeforeText() != null) {
            event.setBeforeText(dots(event.getBeforeText().length()));
        }
    }

    @Override
    public void onPopulateAccessibilityEvent(@NonNull AccessibilityEvent event) {
        super.onPopulateAccessibilityEvent(event);
        if (isMasked()) {
            List<CharSequence> texts = event.getText();
            for (int i = 0; i < texts.size(); i++) {
                CharSequence t = texts.get(i);
                // 不轉成 String 比對（不多產生一份明文）；提示文字照常保留
                if (t != null && t.length() > 0 && !TextUtils.equals(t, getHint())) {
                    texts.set(i, dots(t.length()));
                }
            }
        }
    }

    private static String dots(int length) {
        char[] c = new char[length];
        Arrays.fill(c, DOT);
        return new String(c);
    }
}
