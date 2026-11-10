package app.svault.android.shell.start;

import android.app.Activity;
import android.content.Intent;
import app.svault.android.shell.entries.HomeActivity;

/**
 * 解鎖畫面要離開時去哪裡（T-36 從 {@code UnlockActivity} 拆出）：
 * <ul>
 *   <li>一般（從歡迎畫面、上鎖導回）：解鎖成功清掉工作開首頁；改開其他檔案、沒有可解鎖的保管庫時開歡迎畫面。</li>
 *   <li>回程（{@link UnlockActivity#returnIntent}，模組自己開的頁面遇到上鎖）：解鎖成功以 {@code RESULT_OK} 結束、回到
 *       開它的頁面；其他離開方式都以 {@code RESULT_CANCELED} 結束。不清掉工作、不開首頁：這個工作可能是別的 App 的
 *       （自動填入的驗證流程，Architecture §1.1）。</li>
 * </ul>
 */
final class UnlockExits {

    private final Activity activity;
    private final boolean returning;

    /**
     * @param activity  解鎖畫面
     * @param returning 是不是回程（解鎖後回到開它的頁面）
     */
    UnlockExits(Activity activity, boolean returning) {
        this.activity = activity;
        this.returning = returning;
    }

    /** @return 是不是回程 */
    boolean returning() {
        return returning;
    }

    /**
     * 解鎖成功。
     *
     * @param restored 這次是從備份還原後解鎖（首頁顯示「已還原」）
     */
    void unlocked(boolean restored) {
        if (returning) {
            activity.setResult(Activity.RESULT_OK);
        } else {
            activity.startActivity(new Intent(activity, HomeActivity.class)
                    .putExtra(HomeActivity.EXTRA_RESTORED, restored)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        }
        activity.finish();
    }

    /** 不解鎖這個保管庫：「開啟其他檔案」、最近開啟清單已經空了、沒有本機資料夾。 */
    void leave() {
        if (returning) {
            activity.setResult(Activity.RESULT_CANCELED);
        } else {
            activity.startActivity(WelcomeActivity.stayIntent(activity));
        }
        activity.finish();
    }
}
