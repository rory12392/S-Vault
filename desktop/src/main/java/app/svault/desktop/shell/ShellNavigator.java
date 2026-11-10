package app.svault.desktop.shell;

import app.svault.core.module.EntryNavigator;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Platform;

/**
 * desktop 的 {@link EntryNavigator}（T-30）。模組在啟動時（{@code attach}）就拿到它，但條目畫面要解鎖後才有：
 * 解鎖時外殼以 {@link #attach} 接上條目畫面的「開啟條目」，上鎖時 {@link #detach}；沒有接上時呼叫不做事
 * （Architecture §6「已上鎖」）。從其他執行緒呼叫時轉到 JavaFX 執行緒。
 */
public final class ShellNavigator implements EntryNavigator {

    private static final Logger LOG = Logger.getLogger(ShellNavigator.class.getName());

    private final BooleanSupplier onUiThread;
    private final Executor ui;
    /** 只在 UI 執行緒讀寫。 */
    private Consumer<UUID> target;

    /** 正式程式：JavaFX 執行緒。 */
    public ShellNavigator() {
        this(Platform::isFxApplicationThread, Platform::runLater);
    }

    /**
     * @param onUiThread 目前是否在 UI 執行緒
     * @param ui         把工作排到 UI 執行緒
     */
    ShellNavigator(BooleanSupplier onUiThread, Executor ui) {
        this.onUiThread = onUiThread;
        this.ui = ui;
    }

    /**
     * 解鎖後（JavaFX 執行緒）：之後的呼叫交給條目畫面。
     *
     * @param open 條目畫面的「開啟條目」
     */
    public void attach(Consumer<UUID> open) {
        target = open;
    }

    /** 上鎖時（JavaFX 執行緒）：之後的呼叫不做事。 */
    public void detach() {
        target = null;
    }

    @Override
    public void openEntry(UUID entryId) {
        if (!onUiThread.getAsBoolean()) {
            ui.execute(() -> openEntry(entryId));
            return;
        }
        Consumer<UUID> now = target;
        if (now == null) {
            // 已上鎖：模組頁已被解鎖畫面換掉，通常是上鎖前排進來的呼叫
            LOG.log(Level.FINE, "已上鎖，不開啟條目");
            return;
        }
        now.accept(entryId);
    }
}
