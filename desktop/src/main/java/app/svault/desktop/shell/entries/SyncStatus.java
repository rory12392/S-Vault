package app.svault.desktop.shell.entries;

import app.svault.core.shell.VaultOps;
import app.svault.core.sync.MergeNotice;
import app.svault.core.sync.SyncReport;
import app.svault.desktop.ui.Banner;
import app.svault.desktop.ui.Toast;
import java.text.MessageFormat;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 三欄畫面的存檔與同步狀態（T-14；同步 Banner）：工具列狀態文字、清單上方的 Banner
 * （「正在同步最新資料…」「尚未同步，稍後自動重試」「有 n 筆衝突需要處理」）、合併後的 Toast，以及什麼時候去讀遠端：
 * 解鎖後一次、視窗取得焦點時（至少間隔 30 秒，{@link VaultOps#refreshDue()}）、尚未同步時每 60 秒自動重試。
 *
 * <p>狀態只用操作結果更新（{@link VaultOps.Outcome#state}），不在 JavaFX 執行緒問 {@code VaultSession}：背景同步時它被
 * 鎖住，會卡住畫面。衝突的「處理」開 {@link ConflictFlow}（T-15）。
 */
final class SyncStatus {

    /** 很快就結束的同步不閃一下 Banner。 */
    private static final Duration BANNER_DELAY = Duration.millis(300);

    private final VaultScreen screen;
    private final ResourceBundle text;
    private final String vaultName;
    private final PauseTransition bannerDelay = new PauseTransition(BANNER_DELAY);
    private final Timeline retry;
    private final ChangeListener<Boolean> focus = (obs, old, now) -> {
        if (now) {
            syncIfDue();
        }
    };

    private VaultOps.SyncState state = VaultOps.SyncState.SYNCED;
    private int conflicts;
    private boolean syncing;
    /** 下一個結果是衝突解決的寫回：畫面自己顯示「已解決衝突並儲存」，不再顯示「已更新為最新資料」。 */
    private boolean resolving;
    private Window window;

    /**
     * @param screen    三欄畫面
     * @param text      字串
     * @param vaultName 工具列上的保管庫名稱
     */
    SyncStatus(VaultScreen screen, ResourceBundle text, String vaultName) {
        this.screen = screen;
        this.text = text;
        this.vaultName = vaultName;
        bannerDelay.setOnFinished(e -> showBanner(Banner.Kind.INFO, "banner.syncing", null));
        retry = new Timeline(new KeyFrame(Duration.millis(VaultOps.RETRY_INTERVAL.toMillis()), e -> {
            if (state == VaultOps.SyncState.PENDING) {
                syncNow();
            }
        }));
        retry.setCycleCount(Animation.INDEFINITE);
    }

    /**
     * 內容載入後：開始自動重試與「視窗取得焦點」的檢查，並馬上讀一次遠端（AC-07-5：解鎖後自動讀最新版本）。
     *
     * @param owner 主視窗；沒有時（測試）不監看焦點
     */
    void start(Window owner) {
        window = owner;
        if (window != null) {
            window.focusedProperty().addListener(focus);
        }
        retry.play();
        sync(out -> {
            // AC-08-5：上次衝突沒解決就上鎖、現在仍有衝突 → 直接回到對照畫面（沒有時只顯示 Banner）
            if (state == VaultOps.SyncState.CONFLICT) {
                screen.conflicts().resume();
            }
        });
    }

    /** 上鎖：停止計時與監看。 */
    void dispose() {
        retry.stop();
        bannerDelay.stop();
        if (window != null) {
            window.focusedProperty().removeListener(focus);
            window = null;
        }
    }

    /** @return 目前的同步狀態（JavaFX 執行緒） */
    VaultOps.SyncState state() {
        return state;
    }

    /** 回到前景（視窗取得焦點）：距離上次讀遠端夠久才讀。 */
    void syncIfDue() {
        if (screen.data() != null && screen.ops().refreshDue()) {
            syncNow();
        }
    }

    /** 讀遠端的最新版本並合併；有寫入或編輯進行中時不做（之後的焦點或計時會再試）。 */
    void syncNow() {
        sync(out -> { });
    }

    private void sync(Consumer<VaultOps.Outcome> then) {
        if (screen.busy() || screen.editFlow().editing()) {
            return;
        }
        syncing = true;
        bannerDelay.playFromStart();
        screen.apply(screen.ops()::refresh, null, then, this::stopSyncing);
    }

    /**
     * @param busy 寫入或同步進行中
     */
    void working(boolean busy) {
        if (busy) {
            screen.shell().setVaultStatus(vaultName, text.getString(syncing ? "status.syncing" : "status.saving"));
        }
    }

    /**
     * 寫入或同步完成後（JavaFX 執行緒）：更新狀態文字與 Banner，合併了另一台的修改時顯示 Toast。
     *
     * @param out 結果
     */
    void show(VaultOps.Outcome out) {
        state = out.state(state);
        if (out.sync() != null && out.sync().hasConflicts()) {
            conflicts = out.sync().conflicts();
        }
        stopSyncing();
        boolean announce = !resolving;
        resolving = false;
        if (out.merged()) {
            toastMerge(out.sync(), announce);
        }
    }

    /**
     * @param value 接下來的結果是衝突解決的寫回（合併的通知照常顯示，只是不顯示「已更新為最新資料」）；
     *              寫回失敗時呼叫端傳 {@code false} 還原
     */
    void resolving(boolean value) {
        resolving = value;
    }

    // 依狀態畫 Banner 與工具列狀態文字（同步中的 Banner 在結束時換掉）
    private void render() {
        switch (state) {
            case PENDING:
                showBanner(Banner.Kind.WARNING, "banner.unsaved", "banner.retry");
                screen.shell().setVaultStatus(vaultName, text.getString("status.unsaved"));
                break;
            case CONFLICT:
                showBanner(Banner.Kind.ERROR, "banner.conflicts", "banner.resolve");
                screen.shell().setVaultStatus(vaultName, text.getString("status.conflicts"));
                break;
            default:
                screen.list().setBanner(null);
                screen.shell().setVaultStatus(vaultName, "");
                break;
        }
    }

    private void toastMerge(SyncReport report, boolean announce) {
        if (announce) {
            screen.shell().toasts().show(Toast.Kind.INFO,
                    MessageFormat.format(text.getString("toast.merged"), report.changedEntries()));
        }
        for (Map.Entry<MergeNotice.Kind, Integer> n : report.noticeCounts().entrySet()) {
            String key = "notice." + n.getKey().name() + (n.getValue() == 1 ? ".one" : ".many");
            screen.shell().toasts().show(Toast.Kind.INFO, MessageFormat.format(text.getString(key), n.getValue()));
        }
    }

    private void stopSyncing() {
        syncing = false;
        bannerDelay.stop();
        render();
    }

    private void showBanner(Banner.Kind kind, String key, String actionKey) {
        String message = MessageFormat.format(text.getString(key), conflicts);
        Banner banner = actionKey == null ? new Banner(kind, message)
                : new Banner(kind, message, text.getString(actionKey),
                        kind == Banner.Kind.ERROR ? screen.conflicts()::open : screen.editFlow()::retrySave);
        banner.setId(kind == Banner.Kind.INFO ? "banner-syncing"
                : kind == Banner.Kind.WARNING ? "banner-unsaved" : "banner-conflicts");
        // 動作按鈕的 id（「再試一次」「處理」）給畫面腳本用
        Node action = banner.lookup(".sv-banner-action");
        if (action != null) {
            action.setId(banner.getId() + "-action");
        }
        screen.list().setBanner(banner);
    }
}
