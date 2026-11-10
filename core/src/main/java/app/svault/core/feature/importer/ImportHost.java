package app.svault.core.feature.importer;

import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.UnlockedEntries;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * {@link ImportWizardModel} 用到的外殼服務（T-117）：兩個平台的 {@code ModuleHost} 各自轉接這三個方法，規則照各平台
 * {@code ModuleHost} 的同名方法（T-31 唯讀查詢、T-33 整批新增）。
 */
public interface ImportHost {

    /** @return 這次解鎖的唯讀查詢；上鎖或條目還在載入時為空 */
    Optional<UnlockedEntries> unlockedEntries();

    /**
     * 條目載入好時在 {@code executor} 上通知一次。
     *
     * @return 已上鎖、不會通知時為 {@code false}
     */
    boolean whenEntriesLoaded(Executor executor, Runnable callback);

    /** 整批新增條目到「Chrome 匯入」群組並存檔一次；結果在 {@code executor} 上通知。 */
    void addEntries(EntryBatch batch, Executor executor, Consumer<EntryBatchResult> callback);
}
