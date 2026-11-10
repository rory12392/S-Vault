package app.svault.android.ui;

import android.content.Context;
import android.content.DialogInterface;
import android.widget.Button;
import androidx.appcompat.app.AlertDialog;
import app.svault.android.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 確認對話框：標題寫動作、內文寫後果、確認鈕寫動詞。
 * 危險操作的確認鈕用 danger 色，預設焦點在「取消」（連按 Enter 或誤觸時不會直接刪除）。
 */
public final class ConfirmDialogs {

    private ConfirmDialogs() {
    }

    /**
     * @param context   畫面
     * @param title     「刪除這筆條目？」
     * @param message   後果
     * @param confirm   確認鈕的動詞（「刪除」）
     * @param danger    破壞性操作時為 {@code true}
     * @param onConfirm 按下確認鈕時
     * @return 已顯示的對話框
     */
    public static AlertDialog show(Context context, CharSequence title, CharSequence message, CharSequence confirm,
            boolean danger, DialogInterface.OnClickListener onConfirm) {
        return show(context, title, message, confirm, context.getString(R.string.sv_dialog_cancel), danger, onConfirm);
    }

    /**
     * 取消鈕不是「取消」時（例如「捨棄修改？」的「繼續編輯」）。
     *
     * @param context   畫面
     * @param title     標題
     * @param message   後果
     * @param confirm   確認鈕的動詞
     * @param cancel    取消鈕的文字
     * @param danger    破壞性操作時為 {@code true}
     * @param onConfirm 按下確認鈕時
     * @return 已顯示的對話框
     */
    public static AlertDialog show(Context context, CharSequence title, CharSequence message, CharSequence confirm,
            CharSequence cancel, boolean danger, DialogInterface.OnClickListener onConfirm) {
        AlertDialog dialog = new MaterialAlertDialogBuilder(context,
                danger ? R.style.ThemeOverlay_SVault_Dialog_Danger : R.style.ThemeOverlay_SVault_Dialog)
                .setTitle(title)
                .setMessage(message)
                .setNegativeButton(cancel, null)
                .setPositiveButton(confirm, onConfirm)
                .create();
        if (danger) {
            // 鍵盤操作時的初始焦點；不設 focusableInTouchMode，否則觸控時第一下只會聚焦不會按下
            dialog.setOnShowListener(d -> {
                Button negative = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
                negative.requestFocus();
            });
        }
        // 內文可能有條目或群組名稱（AC-12-4）
        SecureWindows.inherit(dialog).show();
        return dialog;
    }
}
