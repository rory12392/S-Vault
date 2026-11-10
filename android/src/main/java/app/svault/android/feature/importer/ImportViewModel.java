package app.svault.android.feature.importer;

import android.content.Context;
import android.util.Log;
import androidx.lifecycle.ViewModel;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.feature.importer.ImportHost;
import app.svault.core.feature.importer.ImportWizardModel;
import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.UnlockedEntries;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 匯入精靈的狀態，Activity 範圍（CONVENTIONS §11）：旋轉、切換深色模式、改字體時外殼重新建立
 * {@link ImportWizardFragment}，讀檔或匯入中的進度與預覽留在這裡；頁面所在的 Activity 結束（返回、上鎖時整個工作換成
 * 解鎖畫面）時 {@link #onCleared()} 放掉 CSV 的明文。匯入已經交給外殼的話照常完成，只是不再顯示結果。
 */
public final class ImportViewModel extends ViewModel {

    private static final String TAG = "SVault";

    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "svault-importer");
        // 不擋行程結束
        t.setDaemon(true);
        return t;
    });
    private ImportWizardModel model;

    /** 給 {@code ViewModelProvider} 建立實例。 */
    public ImportViewModel() {
        // 第一次取 model 時才接上 host（要 Context）
    }

    /**
     * @param context 頁面的 Context（只用來取 {@link ModuleHost} 與主執行緒，不保存）
     * @return 這個頁面的狀態
     */
    ImportWizardModel model(Context context) {
        if (model == null) {
            // 背景讀檔與匯入回呼的錯誤邊界：只記類型（例外訊息可能帶檔名或內容），畫面由模型改成錯誤狀態
            ModuleGuard guard = new ModuleGuard((id, action, error) ->
                    Log.w(TAG, "匯入出錯（" + action + "）：" + error.getClass().getName()));
            model = new ImportWizardModel(importHost(ModuleHost.of(context)), guard, background,
                    context.getApplicationContext().getMainExecutor());
        }
        return model;
    }

    /**
     * @param host 外殼服務
     * @return core 的匯入精靈用到的三個服務（T-117），直接轉給 {@code host}；寫入帶本模組的代號
     */
    static ImportHost importHost(ModuleHost host) {
        return new ImportHost() {
            @Override
            public Optional<UnlockedEntries> unlockedEntries() {
                return host.unlockedEntries();
            }

            @Override
            public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                return host.whenEntriesLoaded(executor, callback);
            }

            @Override
            public void addEntries(EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback) {
                host.addEntries(ImporterAndroidFeature.ID, batch, executor, callback);
            }
        };
    }

    @Override
    protected void onCleared() {
        if (model != null) {
            model.clear();
        }
        background.shutdownNow();
    }
}
