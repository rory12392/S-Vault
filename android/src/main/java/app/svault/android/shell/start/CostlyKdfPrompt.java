package app.svault.android.shell.start;

import android.content.Context;
import app.svault.android.R;
import app.svault.android.ui.SecureWindows;
import app.svault.core.vault.KdfCost;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 金鑰衍生成本超過門檻時的確認（C-4，D-186）：先說明預估時間，使用者按「仍要開啟」才真的跑。主密碼欄位保留，不必重打。
 */
final class CostlyKdfPrompt {

    /** Pixel 9 Pro 跑一次預設參數（Argon2d 64 MiB、2 次）約 1.9 秒（PRD §3），只用來顯示預估時間。 */
    private static final double BASELINE_SECONDS = 2.0;

    private CostlyKdfPrompt() {
    }

    /**
     * @param context   畫面
     * @param factor    成本倍數（{@code OpenFailure#messageArg()}）
     * @param confirmed 使用者按「仍要開啟」時執行
     */
    static void show(Context context, String factor, Runnable confirmed) {
        double f = Double.parseDouble(factor);
        long seconds = Math.max(1, Math.round(KdfCost.estimatedSeconds(f, BASELINE_SECONDS)));
        SecureWindows.inherit(new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.unlock_costly_title)
                .setMessage(context.getString(R.string.unlock_costly_body, seconds, factor))
                .setNegativeButton(R.string.sv_dialog_cancel, null)
                .setPositiveButton(R.string.unlock_costly_ok, (d, w) -> confirmed.run())
                .create()).show();
    }
}
