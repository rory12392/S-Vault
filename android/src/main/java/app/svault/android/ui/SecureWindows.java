package app.svault.android.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.view.Window;
import android.view.WindowManager;
import androidx.annotation.Nullable;

/**
 * 防截圖（AC-12-4）的視窗設定。顯示保管庫內容的 Activity 由 {@code SecureActivity} 設 {@code FLAG_SECURE}；
 * <b>對話框是另一個視窗，不繼承 Activity 的設定</b>（T-19 實測：沒設時截圖拍得到對話框，Activity 的部分才是黑的），
 * 所以每個對話框都要在顯示之前經過這裡（{@code SecureWindowRulesTest} 檢查）。彈出選單（PopupMenu、Toolbar 的
 * 溢位選單）是 Activity 視窗的子視窗，跟著 Activity 一起保護，不用另外設定。
 */
public final class SecureWindows {

    private SecureWindows() {
    }

    /**
     * 對話框跟著它所在的畫面：畫面有 {@code FLAG_SECURE} 時對話框也設（歡迎、解鎖、備份還原等不顯示保管庫內容的畫面
     * 照常可以截圖）。在 {@code show()} 之前呼叫，內容畫出來之前就生效。
     *
     * @param dialog 還沒顯示的對話框
     * @param <D>    對話框型別
     * @return 同一個對話框
     */
    public static <D extends Dialog> D inherit(D dialog) {
        Activity host = activity(dialog.getContext());
        if (host != null && isSecure(host.getWindow())) {
            secure(dialog.getWindow());
        }
        return dialog;
    }

    /**
     * 一律設定（全螢幕的衝突對照等本身就顯示保管庫內容的對話框）。
     *
     * @param window 視窗；{@code null} 時不做事
     */
    public static void secure(@Nullable Window window) {
        if (window != null) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        }
    }

    /**
     * @param window 視窗
     * @return 有 {@code FLAG_SECURE}
     */
    public static boolean isSecure(@Nullable Window window) {
        return window != null
                && (window.getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0;
    }

    @Nullable
    private static Activity activity(Context context) {
        for (Context c = context; c != null; c = c instanceof ContextWrapper w ? w.getBaseContext() : null) {
            if (c instanceof Activity a) {
                return a;
            }
        }
        return null;
    }
}
