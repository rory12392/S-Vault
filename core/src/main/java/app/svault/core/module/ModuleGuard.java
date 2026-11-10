package app.svault.core.module;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 功能模組的錯誤邊界：接住模組進入點丟出的例外，記錄並通知外殼，不往上傳（CONVENTIONS §11）。
 *
 * <p>只接住 {@link Exception} 與 {@link LinkageError}（模組缺類別或版本不符）；
 * {@link VirtualMachineError}（例如記憶體不足）照常拋出，因為整個程式已不可靠。
 */
public final class ModuleGuard {

    private static final Logger LOG = Logger.getLogger(ModuleGuard.class.getName());

    private final ModuleFailureListener listener;

    /**
     * @param listener 出錯時的通知對象
     */
    public ModuleGuard(ModuleFailureListener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    /**
     * 執行模組的動作。
     *
     * @param moduleId 模組代號
     * @param action   動作名稱（記錄用，不得含機密）
     * @param body     要執行的動作
     * @return 成功時為 {@code true}；被接住例外時為 {@code false}
     */
    public boolean run(String moduleId, String action, Runnable body) {
        return call(moduleId, action, () -> {
            body.run();
            return Boolean.TRUE;
        }).isPresent();
    }

    /**
     * 執行有回傳值的模組動作。
     *
     * @param moduleId 模組代號
     * @param action   動作名稱（記錄用，不得含機密）
     * @param body     要執行的動作；回傳 {@code null} 視為沒有結果
     * @param <T>      回傳型別
     * @return 動作的結果；出錯或回傳 {@code null} 時為空
     */
    public <T> Optional<T> call(String moduleId, String action, Supplier<T> body) {
        try {
            return Optional.ofNullable(body.get());
        } catch (Exception | LinkageError e) {
            report(moduleId, action, e);
            return Optional.empty();
        }
    }

    /**
     * 記錄並通知一個已被接住的模組錯誤（探索階段等無法用 {@link #call} 包住的情況）。
     *
     * @param moduleId 模組代號
     * @param action   動作名稱
     * @param error    例外
     */
    public void report(String moduleId, String action, Throwable error) {
        // 只記例外類型與模組資訊：例外訊息可能含模組處理中的資料，不寫進日誌
        LOG.log(Level.WARNING, "功能模組出錯：module={0} action={1} error={2}",
                new Object[] {moduleId, action, error.getClass().getName()});
        try {
            listener.onFailure(moduleId, action, error);
        } catch (RuntimeException listenerError) {
            LOG.log(Level.WARNING, "模組錯誤通知失敗：{0}", listenerError.getClass().getName());
        }
    }
}
