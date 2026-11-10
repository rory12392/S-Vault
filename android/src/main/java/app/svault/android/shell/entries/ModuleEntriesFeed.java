package app.svault.android.shell.entries;

import androidx.annotation.MainThread;
import androidx.annotation.WorkerThread;
import app.svault.core.shell.ModuleVaultAccess;
import app.svault.core.shell.VaultOps;

/**
 * 把條目畫面的內容交給功能模組的唯讀查詢（T-31，{@link ModuleVaultAccess}）：{@link EntriesModel} 每次換新快照就發布到
 * 這次解鎖的租約。放在這個套件是因為要用 {@link EntriesModel} 的套件內方法（它已在行數上限，不再加公開方法）。
 */
public final class ModuleEntriesFeed {

    private ModuleEntriesFeed() {
    }

    /**
     * 讓 {@code model} 的內容發布到 {@code lease}（已有內容時立即發布一次）。上鎖後 {@code model} 跟著被丟掉，
     * 作廢的租約不收新的內容，所以不用移除觀察者。
     *
     * @param model 這次解鎖的條目狀態
     * @param lease 這次解鎖的租約
     */
    @MainThread
    public static void attach(EntriesModel model, ModuleVaultAccess.Lease lease) {
        model.data().observeForever(lease::publish);
    }

    /**
     * 模組要內容、但還沒有條目畫面載入過時（例如只解鎖、還沒到首頁）開始載入；已在載入或已載入時不做事。
     *
     * @param model 這次解鎖的條目狀態
     */
    @MainThread
    public static void load(EntriesModel model) {
        model.ensureLoaded();
    }

    /**
     * 模組寫入條目存檔成功後（T-33，{@code ModuleVaultWriter} 的 {@code onSaved}）：條目畫面換成新的內容、同步狀態跟著更新，
     * 和畫面自己的操作完成時相同（新內容也經 {@link #attach} 發布給模組）。
     *
     * @param model 這次解鎖的條目狀態；還沒建立時 {@code null}（之後建立時本來就讀最新的內容）
     * @param out   寫入的結果
     */
    @WorkerThread
    public static void deliver(EntriesModel model, VaultOps.Outcome out) {
        if (model != null) {
            model.deliver(out, true);
        }
    }
}
