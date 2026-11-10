package app.svault.desktop.shell;

import app.svault.core.vault.VaultException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.concurrent.Task;

/**
 * 保管庫操作的背景執行緒（CONVENTIONS §2：不在 UI 執行緒做 I/O 或 KDF）。只有一條執行緒，所以解鎖、建立、
 * 編輯與存檔、上鎖依序執行，上鎖不會在解鎖或存檔途中清掉金鑰；結果與失敗都回到 JavaFX 執行緒處理。
 */
public final class VaultWorker {

    private static final Logger LOG = Logger.getLogger(VaultWorker.class.getName());

    /** 背景執行的保管庫操作。 */
    @FunctionalInterface
    public interface Work {
        /**
         * @throws VaultException 保管庫操作失敗
         */
        void run() throws VaultException;
    }

    /**
     * 背景執行、有結果的保管庫操作。
     *
     * @param <T> 結果型別
     */
    @FunctionalInterface
    public interface Supply<T> {
        /**
         * @return 結果
         * @throws VaultException 保管庫操作失敗
         */
        T get() throws VaultException;
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "svault-vault");
        t.setDaemon(true);
        return t;
    });
    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(this, "busy", false);
    /** 開發版畫面腳本的「卡住第 n 個工作」：只在 JavaFX 執行緒讀寫。 */
    private int holdCountdown;
    private CountDownLatch holdLatch;

    /**
     * 在 JavaFX 執行緒呼叫。失敗時只記例外類型（訊息可能含路徑以外的內容，CONVENTIONS §3）。
     *
     * @param work      背景工作
     * @param onSuccess 成功後（JavaFX 執行緒）
     * @param onFailure 失敗後（JavaFX 執行緒）
     */
    public void run(Work work, Runnable onSuccess, Consumer<Exception> onFailure) {
        supply(() -> {
            work.run();
            return null;
        }, result -> onSuccess.run(), onFailure);
    }

    /**
     * 在 JavaFX 執行緒呼叫；同 {@link #run}，但把背景工作的結果交給 {@code onSuccess}。
     *
     * @param <T>       結果型別
     * @param work      背景工作
     * @param onSuccess 成功後（JavaFX 執行緒）
     * @param onFailure 失敗後（JavaFX 執行緒）
     */
    public <T> void supply(Supply<T> work, Consumer<T> onSuccess, Consumer<Exception> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.get();
            }
        };
        busy.set(true);
        task.setOnSucceeded(e -> {
            busy.set(false);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            busy.set(false);
            Throwable t = task.getException();
            LOG.log(Level.INFO, "保管庫操作失敗：{0}", t.getClass().getName());
            onFailure.accept(t instanceof Exception ex ? ex : new IllegalStateException(t.getClass().getName()));
        });
        submit(task);
    }

    /** @param action 排在目前工作之後執行，不追蹤結果（上鎖、預先載入字典） */
    public void execute(Runnable action) {
        submit(action);
    }

    /** @return 是否有 {@link #run}／{@link #supply} 的工作進行中 */
    public ReadOnlyBooleanProperty busyProperty() {
        return busy.getReadOnlyProperty();
    }

    /**
     * 開發版畫面腳本用（{@code hold=n}）：在之後第 {@code n} 個送進來的工作前面卡住，直到呼叫回傳的動作。
     * 用來截「載入中」「儲存中」這類一閃而過的狀態；正式程式不呼叫。
     *
     * @param n 第幾個（1 起算）
     * @return 放行
     */
    public Runnable holdBefore(int n) {
        holdCountdown = Math.max(1, n);
        holdLatch = new CountDownLatch(1);
        CountDownLatch latch = holdLatch;
        return latch::countDown;
    }

    private void submit(Runnable action) {
        if (holdCountdown > 0 && --holdCountdown == 0) {
            CountDownLatch latch = holdLatch;
            executor.execute(() -> {
                try {
                    latch.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        executor.execute(action);
    }

    /**
     * 停止接受新工作，等排隊中的工作做完（最多 5 秒，進行中的存檔要寫完）。
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                LOG.warning("結束時背景工作仍未完成");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
