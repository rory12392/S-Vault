package app.svault.android.shell.ext;

import androidx.annotation.StringRes;
import androidx.fragment.app.FragmentActivity;

/**
 * 解鎖畫面的替代解鎖方式（例如 BIO 的生物辨識）。
 */
public interface UnlockProvider {

    /** 解鎖流程的結果回呼，在 UI 執行緒呼叫。 */
    interface Callback {

        /**
         * 取得主密碼。外殼解鎖後會把陣列清零。
         *
         * @param masterPassword 主密碼的 UTF-8 位元組
         */
        void onSuccess(byte[] masterPassword);

        /** 使用者取消；外殼留在主密碼輸入。 */
        void onCancel();

        /**
         * 失敗；外殼退回主密碼輸入（AC-11-4）。
         *
         * @param error 失敗原因（不含機密）
         */
        void onFailure(Throwable error);
    }

    /** @return 按鈕文字的字串資源 */
    @StringRes
    int labelRes();

    /**
     * @param vaultId 保管庫 ID（根群組 UUID）
     * @return 這個保管庫可以用此方式解鎖時為 {@code true}
     */
    boolean isAvailable(String vaultId);

    /**
     * 開始解鎖流程。
     *
     * @param activity 解鎖畫面
     * @param vaultId  保管庫 ID
     * @param callback 結果回呼
     */
    void start(FragmentActivity activity, String vaultId, Callback callback);
}
