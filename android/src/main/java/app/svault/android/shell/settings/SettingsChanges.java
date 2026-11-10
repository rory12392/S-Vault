package app.svault.android.shell.settings;

import android.util.Log;
import app.svault.core.storage.LiveSettings;
import app.svault.core.storage.Settings;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

/**
 * 設定畫面的變更（立即生效、立即寫入 {@code settings.json}，沒有「儲存」按鈕；D-123）。
 * 記憶體中的設定在主執行緒上立刻換掉（主題當場切換、T-18／T-19 讀到最新值），寫檔放背景執行緒
 * （CONVENTIONS §2：不在 UI 執行緒做 I/O）。同 desktop 的 {@code SettingsSaver}。
 */
public final class SettingsChanges {

    private static final String TAG = "SVault";

    private final LiveSettings settings;
    private final Executor background;
    private final Executor main;

    /**
     * @param settings   執行中的設定
     * @param background 寫檔的背景執行緒
     * @param main       主執行緒（寫檔失敗的回報在這裡執行）
     */
    public SettingsChanges(LiveSettings settings, Executor background, Executor main) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.background = Objects.requireNonNull(background, "background");
        this.main = Objects.requireNonNull(main, "main");
    }

    /**
     * 在主執行緒呼叫。
     *
     * @param change       從目前的設定算出新的設定
     * @param onSaveFailed 寫不進 {@code settings.json} 時（主執行緒），顯示錯誤；設定照常生效到 App 結束
     * @return 變更後的設定
     */
    public Settings change(UnaryOperator<Settings> change, Runnable onSaveFailed) {
        Settings now = settings.update(change);
        background.execute(() -> {
            try {
                // 寫的是寫檔當下最新的設定，連續變更時不會蓋回舊值
                settings.save();
            } catch (IOException e) {
                Log.w(TAG, "無法寫入 settings.json：" + e.getClass().getName());
                main.execute(onSaveFailed);
            }
        });
        return now;
    }

    /** @return 目前的設定 */
    public Settings current() {
        return settings.current();
    }

    /** @return 執行中的設定（T-18、T-19 讀值或註冊監聽） */
    public LiveSettings live() {
        return settings;
    }
}
