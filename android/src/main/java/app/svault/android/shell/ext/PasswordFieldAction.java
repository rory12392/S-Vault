package app.svault.android.shell.ext;

import android.content.Context;
import androidx.annotation.StringRes;

/**
 * 編輯畫面密碼欄旁的按鈕（例如 GEN 的「產生」）。
 *
 * <p>外殼按下按鈕時呼叫 {@link #perform(Context, PasswordFill)}。要當場算出值的模組只實作同步的
 * {@link #perform(Context)}；要先開自己的畫面、等使用者確認再回填的模組（GEN 的底部 sheet，T-37）覆寫非同步版，
 * 之後用 {@link PasswordFill#fill} 回填，同步版回傳 {@code null}。兩個都由外殼包在 {@code ModuleGuard} 裡呼叫。
 */
public interface PasswordFieldAction {

    /** @return 按鈕文字的字串資源 */
    @StringRes
    int labelRes();

    /**
     * 執行動作。外殼把結果填進密碼欄後會把陣列清零。
     *
     * @param context 目前的畫面
     * @return 要填入的新密碼；不填入時回傳 {@code null}
     */
    char[] perform(Context context);

    /**
     * 執行動作，之後再回填（T-37）。在主執行緒呼叫，要馬上回傳：要等使用者的畫面（例如
     * {@code BottomSheetDialogFragment}，用 {@code context} 的 {@code FragmentManager} 顯示、視窗經 {@code SecureWindows}）
     * 自己開，確認時呼叫 {@code fill.fill(值)}；取消時不用呼叫。{@code fill} 的有效期間與清零規則見 {@link PasswordFill}。
     *
     * <p>預設：呼叫同步的 {@link #perform(Context)}，有值時立即回填（既有模組的行為不變）。
     *
     * @param context 目前的畫面（編輯畫面的 Activity，是 {@code FragmentActivity}）
     * @param fill    回填密碼欄
     */
    default void perform(Context context, PasswordFill fill) {
        char[] value = perform(context);
        if (value != null) {
            fill.fill(value);
        }
    }
}
