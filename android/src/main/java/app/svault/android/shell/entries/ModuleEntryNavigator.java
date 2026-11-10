package app.svault.android.shell.entries;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import app.svault.android.R;
import app.svault.android.ui.Toasts;
import app.svault.core.module.EntryNavigator;
import app.svault.core.shell.EntryTarget;
import app.svault.core.shell.ScreenData;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Android 的 {@link EntryNavigator}（T-30；Architecture §6，判斷在 {@link EntryTarget}）：開 {@link EntryDetailActivity}，
 * 疊在呼叫的畫面（模組頁）上，返回回到模組頁；回收桶裡的條目一樣開，詳細頁顯示「還原」「永久刪除」。保管庫裡沒有這筆時
 * 在呼叫的畫面上顯示 error Snackbar「找不到這筆條目，它可能已被刪除」，不換畫面；已上鎖時不做事（畫面本來就會導回
 * 解鎖畫面）。內容還在載入時照樣開詳細（詳細頁等內容載入後才顯示；不在就關掉）。
 */
public final class ModuleEntryNavigator implements EntryNavigator {

    private static final String TAG = "SVault";

    private final Context context;
    private final Supplier<EntriesModel> entries;

    /**
     * @param context 呼叫的畫面
     * @param entries 這次解鎖的條目狀態；已上鎖時回傳 {@code null}（在主執行緒呼叫）
     */
    public ModuleEntryNavigator(Context context, Supplier<EntriesModel> entries) {
        this.context = context;
        this.entries = entries;
    }

    @Override
    public void openEntry(UUID entryId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            context.getMainExecutor().execute(() -> openEntry(entryId));
            return;
        }
        EntriesModel model = entries.get();
        if (model == null) {
            Log.d(TAG, "已上鎖，不開啟條目");
            return;
        }
        ScreenData data = model.data().getValue();
        if (entryId == null || (data != null && !EntryTarget.resolve(data, entryId).opens())) {
            showNotFound();
            return;
        }
        Intent detail = EntryDetailActivity.intent(context, entryId);
        if (!(context instanceof Activity)) {
            detail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(detail);
    }

    private void showNotFound() {
        if (context instanceof Activity activity && !activity.isFinishing()) {
            View anchor = activity.findViewById(android.R.id.content);
            if (anchor != null) {
                Toasts.show(anchor, context.getString(R.string.entries_toast_not_found), Toasts.Kind.ERROR);
                return;
            }
        }
        Log.d(TAG, "找不到要開啟的條目，沒有畫面可以顯示提示");
    }
}
