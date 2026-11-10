package app.svault.desktop.shell.start;

import app.svault.core.session.AutoLock;
import app.svault.core.session.IdleTimer;
import app.svault.core.session.LockSignalPort;
import app.svault.core.storage.LiveSettings;
import java.time.Clock;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.event.EventHandler;
import javafx.scene.Scene;
import javafx.scene.input.InputEvent;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * desktop 的自動上鎖（T-18；AC-12-1）：任何 S-Vault 視窗（主視窗與對話框）裡的鍵盤、滑鼠操作都重新計時，閒置滿設定的
 * 分鐘數（{@link LiveSettings}，設定頁改了立即套用；0＝不自動上鎖）就上鎖；Windows 鎖定螢幕時（{@link LockSignalPort}）
 * 立即上鎖。上鎖動作是 {@link StartFlow#lock()}（先關對話框、存檔，AC-12-3）。
 *
 * <p>判斷（{@link #check()}、{@link #onSignal()}）不需要 JavaFX，單元測試直接呼叫；計時器與事件過濾在
 * {@link #install()}（JavaFX 執行緒）。
 */
final class AutoLocker {

    private static final Logger LOG = Logger.getLogger(AutoLocker.class.getName());
    /** 多久檢查一次閒置；上鎖最多晚這麼久。 */
    static final Duration CHECK_EVERY = Duration.seconds(5);

    private final LiveSettings live;
    private final IdleTimer idle;
    private final LockSignalPort signal;
    private final BooleanSupplier unlocked;
    private final Runnable lock;
    private final EventHandler<InputEvent> activity;
    private final ListChangeListener<Window> windows = change -> {
        while (change.next()) {
            change.getAddedSubList().forEach(this::watch);
        }
    };
    private Timeline timer;

    /**
     * @param live     執行中的設定（閒置分鐘數）
     * @param clock    時間來源
     * @param signal   Windows 鎖定螢幕的訊號
     * @param unlocked 目前有解鎖的畫面（JavaFX 執行緒上問，不問背景存檔時被鎖住的 session）
     * @param lock     上鎖（JavaFX 執行緒）
     */
    AutoLocker(LiveSettings live, Clock clock, LockSignalPort signal, BooleanSupplier unlocked, Runnable lock) {
        this.live = Objects.requireNonNull(live, "live");
        this.idle = new IdleTimer(clock);
        this.signal = Objects.requireNonNull(signal, "signal");
        this.unlocked = Objects.requireNonNull(unlocked, "unlocked");
        this.lock = Objects.requireNonNull(lock, "lock");
        this.activity = e -> idle.touch();
        // 改了分鐘數：從現在重新計時（不會因為改短了就立刻上鎖）
        live.addListener(now -> idle.touch());
    }

    /** 開始計時、監看所有視窗的操作與 Windows 鎖定（JavaFX 執行緒）。 */
    void install() {
        Window.getWindows().forEach(this::watch);
        Window.getWindows().addListener(windows);
        timer = new Timeline(new KeyFrame(CHECK_EVERY, e -> check()));
        timer.setCycleCount(Animation.INDEFINITE);
        timer.play();
        if (!signal.start(() -> Platform.runLater(this::onSignal))) {
            LOG.info("這個環境沒有鎖定螢幕的通知，只靠閒置上鎖");
        }
    }

    /** 程式結束：停止計時與監看。 */
    void stop() {
        if (timer != null) {
            timer.stop();
        }
        Window.getWindows().removeListener(windows);
        signal.stop();
    }

    /** 剛解鎖或有操作：重新計時。 */
    void touch() {
        idle.touch();
    }

    /** 計時器：解鎖中而且閒置滿設定的分鐘數就上鎖。 */
    void check() {
        if (unlocked.getAsBoolean()
                && AutoLock.desktopIdleLimit(live.current()).map(idle::expired).orElse(false)) {
            LOG.info("閒置時間到，自動上鎖");
            lock.run();
        }
    }

    /** Windows 鎖定螢幕：解鎖中就立即上鎖，不看閒置設定（AC-12-1）。 */
    void onSignal() {
        if (unlocked.getAsBoolean()) {
            LOG.info("Windows 鎖定螢幕，自動上鎖");
            lock.run();
        }
    }

    private void watch(Window window) {
        // 視窗建立時可能還沒有場景（對話框在 show 時才放）：場景換上時再加過濾
        window.sceneProperty().addListener((obs, old, now) -> {
            if (old != null) {
                old.removeEventFilter(InputEvent.ANY, activity);
            }
            filter(now);
        });
        filter(window.getScene());
    }

    private void filter(Scene scene) {
        if (scene != null) {
            // 同一個場景可能經過這裡兩次（視窗建立時、換上場景時）：先移除再加，不重複掛
            scene.removeEventFilter(InputEvent.ANY, activity);
            scene.addEventFilter(InputEvent.ANY, activity);
        }
    }
}
