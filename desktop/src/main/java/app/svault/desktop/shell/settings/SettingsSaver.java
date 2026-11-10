package app.svault.desktop.shell.settings;

import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 設定畫面的變更（立即生效、立即寫入 {@code settings.json}，沒有「儲存」按鈕）。
 * 記憶體中的設定在 JavaFX 執行緒上立刻換掉（主題當場切換），寫檔放到背景執行緒（CONVENTIONS §2：不在 UI 執行緒做 I/O）。
 */
final class SettingsSaver {

    private static final Logger LOG = Logger.getLogger(SettingsSaver.class.getName());

    private final LiveSettings settings;
    private final Executor background;
    private final Consumer<Runnable> onUi;
    private final Runnable onSaveFailed;

    /**
     * @param settings     執行中的設定
     * @param background   寫檔的背景執行緒
     * @param onUi         回到 JavaFX 執行緒（正式程式是 {@code Platform::runLater}）
     * @param onSaveFailed 寫檔失敗時（JavaFX 執行緒），顯示錯誤
     */
    SettingsSaver(LiveSettings settings, Executor background, Consumer<Runnable> onUi, Runnable onSaveFailed) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.background = Objects.requireNonNull(background, "background");
        this.onUi = Objects.requireNonNull(onUi, "onUi");
        this.onSaveFailed = Objects.requireNonNull(onSaveFailed, "onSaveFailed");
    }

    /**
     * @param change 從目前的設定算出新的設定
     * @return 變更後的設定
     */
    Settings change(UnaryOperator<Settings> change) {
        Settings now = settings.update(change);
        background.execute(() -> {
            try {
                // 寫的是寫檔當下最新的設定，連續變更時不會蓋回舊值
                settings.save();
            } catch (IOException e) {
                LOG.log(Level.WARNING, "無法寫入 settings.json：{0}", e.getClass().getName());
                onUi.accept(onSaveFailed);
            }
        });
        return now;
    }

    /** @return 目前的設定 */
    Settings current() {
        return settings.current();
    }
}
