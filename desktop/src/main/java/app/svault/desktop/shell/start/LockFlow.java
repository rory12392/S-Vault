package app.svault.desktop.shell.start;

import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.LockSignalPort;
import app.svault.core.session.VaultSession;
import app.svault.core.storage.LiveSettings;
import app.svault.desktop.platform.ScreenCaptureGuard;
import app.svault.desktop.platform.WindowsSessionLock;
import app.svault.desktop.shell.AppShell;
import app.svault.desktop.shell.VaultWorker;
import app.svault.desktop.shell.entries.VaultScreen;
import app.svault.desktop.ui.OpenWindows;
import java.time.Clock;
import java.util.ResourceBundle;
import java.util.function.Supplier;
import java.util.logging.Logger;
import javafx.stage.Window;

/**
 * 上鎖（T-18 從 {@link StartFlow} 拆出）：手動（{@code Ctrl+L}、工具列按鈕，AC-12-5）與自動（閒置、Windows 鎖定螢幕，
 * AC-12-1，{@link AutoLocker}）都走 {@link #lock()}。JavaFX 執行緒上先關掉開著的對話框（P-57）、記下編輯畫面上沒儲存的
 * 內容與衝突的選擇、顯示「正在上鎖…」擋住操作；背景執行緒上（排在進行中的解鎖或存檔之後）先存檔再上鎖（AC-12-3、
 * AC-08-5）。{@code VaultSession} 發出上鎖通知後，{@link StartFlow} 回到解鎖畫面並呼叫 {@link #locked()}。
 */
final class LockFlow {

    private static final Logger LOG = Logger.getLogger(LockFlow.class.getName());

    private final AppShell shell;
    private final VaultWorker worker;
    private final VaultSession session;
    private final ResourceBundle text;
    private final Supplier<VaultScreen> vault;
    private final Supplier<Window> window;
    private final LockSignalPort signal;
    private final AutoLocker auto;
    private final ScreenCaptureGuard capture;
    private final ClipboardGuard clipboard;
    private final Runnable modulesOff;
    /** 已開始上鎖、還沒回到解鎖畫面（存檔中）：不重複上鎖。 */
    private boolean locking;

    /**
     * @param shell   主視窗外框
     * @param worker  保管庫的背景執行緒
     * @param session 解鎖狀態
     * @param text    字串
     * @param vault   目前解鎖後的三欄（上鎖時 {@code null}）
     * @param window  主視窗
     * @param live    執行中的設定（閒置分鐘數）
     * @param clock   時間來源
     * @param signal  Windows 鎖定螢幕的訊號
     * @param clipboard 剪貼簿（上鎖時清除還沒清掉的密碼，T-19）
     * @param modulesOff 按下上鎖當下收回模組的唯讀保管庫（T-32；不等背景存檔）
     */
    LockFlow(AppShell shell, VaultWorker worker, VaultSession session, ResourceBundle text,
            Supplier<VaultScreen> vault, Supplier<Window> window, LiveSettings live, Clock clock,
            LockSignalPort signal, ClipboardGuard clipboard, Runnable modulesOff) {
        this.shell = shell;
        this.worker = worker;
        this.session = session;
        this.text = text;
        this.vault = vault;
        this.window = window;
        this.signal = signal;
        this.clipboard = clipboard;
        this.modulesOff = modulesOff;
        this.auto = new AutoLocker(live, clock, signal, () -> vault.get() != null && !locking, this::lock);
        this.capture = new ScreenCaptureGuard(live);
    }

    /** 開始自動上鎖的計時與監看（JavaFX 執行緒）。 */
    void install() {
        auto.install();
        capture.install();
    }

    /** 剛解鎖：閒置從現在算起；阻擋截圖（I-1）；加密設定偏弱時提示升級（L-4）。 */
    void unlocked() {
        auto.touch();
        capture.setUnlocked(true);
        WeakKdfPrompt.maybeShow(shell, worker, session, text);
    }

    /** 已回到解鎖畫面：上鎖可能不是從這裡開始的（例如還原失敗），開著的對話框一樣要關（P-57）。 */
    void locked() {
        locking = false;
        capture.setUnlocked(false);
        OpenWindows.closeAll(window.get());
        clearClipboard();
    }

    /** 上鎖（JavaFX 執行緒）。 */
    void lock() {
        // 和剪貼簿一樣在按下當下：存檔要幾秒，這段時間模組（例如背景算健檢的）不該再讀到內容
        modulesOff.run();
        VaultScreen open = vault.get();
        if (open == null) {
            clearClipboard();
            worker.execute(session::lock);
            return;
        }
        if (locking) {
            return;
        }
        locking = true;
        OpenWindows.closeAll(window.get());
        // AC-05-4：密碼立即清（內容仍相同時），不等背景存檔
        clearClipboard();
        VaultScreen.LockState state = open.prepareLock();
        shell.progress().show(text.getString("lock.locking"));
        worker.execute(() -> {
            try {
                open.lock(state);
            } finally {
                // 存檔出了沒預期的錯也一定上鎖（已上鎖時不做事）
                session.lock();
            }
        });
    }

    // 剪貼簿被別的程式佔用時清不掉：只記類型，上鎖照常進行
    private void clearClipboard() {
        try {
            clipboard.clearNow();
        } catch (RuntimeException e) {
            LOG.warning("上鎖時無法清除剪貼簿：" + e.getClass().getName());
        }
    }

    /** 程式結束：停止計時與監看。 */
    void stop() {
        auto.stop();
    }

    /**
     * 開發版畫面腳本用：模擬 Windows 鎖定螢幕。Windows 上送訊息給 JNA 的通知視窗，走和真的鎖定相同的原生路徑
     * （不真的鎖定電腦）。
     */
    void simulateSessionLock() {
        if (!(signal instanceof WindowsSessionLock w) || !w.simulateLock()) {
            auto.onSignal();
        }
    }
}
