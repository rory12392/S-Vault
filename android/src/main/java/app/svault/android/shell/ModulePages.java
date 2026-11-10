package app.svault.android.shell;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模組開自己頁面的入口（T-36；{@code ModuleHost#pageIntent}、{@code ModuleHost#pageSender} 的實作）：用
 * {@link FeatureHostActivity} 載入模組的 Fragment，模組不用知道它的類別與 extra 鍵。
 *
 * <p>這樣開的頁面遇到上鎖時不清掉工作：在同一個工作裡先開解鎖畫面，解鎖後回到頁面，取消時頁面以
 * {@code RESULT_CANCELED} 結束；顯示中被上鎖時也只結束自己（{@link FeatureHostActivity} 的回程）。從首頁選單開的模組頁面
 * （{@link ModuleMenus}）照舊：上鎖時整個工作換成解鎖畫面。
 */
public final class ModulePages {

    private static final Pattern MODULE_ID = Pattern.compile("[a-z0-9]+");

    private ModulePages() {
    }

    /**
     * @param context  任何屬於這個 App 的 Context（元件明確指定成 {@link FeatureHostActivity}）
     * @param moduleId 模組代號（錯誤紀錄用）
     * @param titleRes 頁面標題（模組自己的字串資源）
     * @param page     頁面的 Fragment（public、有 public 無參數建構子；不是的時候頁面顯示錯誤狀態）
     * @param args     給頁面的參數；沒有時為 {@code null}
     * @return 開啟頁面的 Intent
     * @throws IllegalArgumentException 模組代號格式不對、沒有標題
     */
    public static Intent intent(Context context, String moduleId, @StringRes int titleRes,
            Class<? extends Fragment> page, @Nullable Bundle args) {
        if (moduleId == null || !MODULE_ID.matcher(moduleId).matches()) {
            throw new IllegalArgumentException("模組代號格式不對");
        }
        if (titleRes == 0) {
            throw new IllegalArgumentException("頁面要有標題");
        }
        Objects.requireNonNull(page, "page");
        // 每個 extra 都明確放（沒有參數也放 null）：PendingIntent 可修改時，送出端的 fillIn 只能補沒有的鍵，蓋不掉這些
        return FeatureHostActivity.intent(context, moduleId, titleRes, page, args)
                .putExtra(FeatureHostActivity.EXTRA_TITLE_TEXT, (CharSequence) null)
                .putExtra(FeatureHostActivity.EXTRA_RETURN, true);
    }

    /**
     * 給系統代為開啟的 {@link IntentSender}（AF 的驗證流程：{@code FillResponse.Builder#setAuthentication}）。
     *
     * <p>{@code FLAG_MUTABLE}：自動填入的官方文件要求驗證用的 PendingIntent 可修改（系統要補上
     * {@code EXTRA_ASSIST_STRUCTURE} 等驗證參數）。風險用兩點擋住：元件明確指定（{@code fillIn} 換不掉）、頁面用到的
     * extra 都已經放了值（{@code fillIn} 蓋不掉已有的鍵），所以送出端只能多加頁面不讀的 extra。
     *
     * @param context     任何屬於這個 App 的 Context
     * @param moduleId    模組代號
     * @param titleRes    頁面標題
     * @param page        頁面的 Fragment
     * @param args        給頁面的參數；沒有時為 {@code null}
     * @param requestCode 這次的代號：代號相同的會共用同一個 PendingIntent（參數換成最新的），要並存時用不同代號
     * @return 開啟頁面的 IntentSender
     * @throws IllegalArgumentException 同 {@link #intent}
     */
    public static IntentSender sender(Context context, String moduleId, @StringRes int titleRes,
            Class<? extends Fragment> page, @Nullable Bundle args, int requestCode) {
        Intent intent = intent(context, moduleId, titleRes, page, args);
        return PendingIntent.getActivity(context, requestCode, intent,
                PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT).getIntentSender();
    }
}
