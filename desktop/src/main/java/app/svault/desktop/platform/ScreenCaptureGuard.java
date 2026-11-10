package app.svault.desktop.platform;

import app.svault.core.storage.LiveSettings;
import com.sun.jna.Native;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.collections.ListChangeListener;

/**
 * 解鎖時阻擋螢幕截圖與錄影（I-1，D-189）：對這個程式所有看得到的頂層視窗（主視窗與對話框）設
 * {@code SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)}，截圖、錄影、遠端畫面分享看到的會是黑的或空的。和 Android 的
 * {@code FLAG_SECURE} 同一種防護。Windows 10 2004 以上才支援這個值，更舊的系統呼叫失敗時只記錄，不影響使用。
 * 設定 {@code desktop.blockScreenCapture} 預設開，改了立即套用。只在 JavaFX 執行緒呼叫。非 Windows 不做事。
 */
public final class ScreenCaptureGuard {

    private static final Logger LOG = Logger.getLogger(ScreenCaptureGuard.class.getName());
    private static final int WDA_NONE = 0;
    private static final int WDA_EXCLUDEFROMCAPTURE = 0x11;

    /** jna-platform 的 User32 沒有這個函式。 */
    private interface Affinity extends StdCallLibrary {
        boolean SetWindowDisplayAffinity(HWND hWnd, int affinity);
    }

    private final LiveSettings live;
    private Affinity affinity;
    private boolean unlocked;
    private boolean applied;

    /**
     * @param live 執行中的設定
     */
    public ScreenCaptureGuard(LiveSettings live) {
        this.live = live;
    }

    /** 開始監看設定與新開的視窗（JavaFX 執行緒，主視窗顯示之後）。 */
    public void install() {
        if (!Platform.isWindows()) {
            return;
        }
        live.addListener(now -> javafx.application.Platform.runLater(this::refresh));
        // 解鎖中新開的對話框（條目編輯、確認框）也要套用
        javafx.stage.Window.getWindows().addListener((ListChangeListener<javafx.stage.Window>) c -> {
            if (applied) {
                javafx.application.Platform.runLater(() -> apply(true));
            }
        });
    }

    /** @param now 目前有解鎖的保管庫 */
    public void setUnlocked(boolean now) {
        unlocked = now;
        refresh();
    }

    private void refresh() {
        if (!Platform.isWindows()) {
            return;
        }
        boolean want = unlocked && live.current().blockScreenCapture();
        if (want != applied) {
            apply(want);
        }
    }

    private void apply(boolean block) {
        applied = block;
        try {
            if (affinity == null) {
                affinity = Native.load("user32", Affinity.class, W32APIOptions.DEFAULT_OPTIONS);
            }
            int pid = Kernel32.INSTANCE.GetCurrentProcessId();
            User32.INSTANCE.EnumWindows((hwnd, data) -> {
                IntByReference owner = new IntByReference();
                User32.INSTANCE.GetWindowThreadProcessId(hwnd, owner);
                if (owner.getValue() == pid && User32.INSTANCE.IsWindowVisible(hwnd)
                        && !affinity.SetWindowDisplayAffinity(hwnd, block ? WDA_EXCLUDEFROMCAPTURE : WDA_NONE)) {
                    LOG.log(Level.INFO, "無法設定視窗的截圖保護（錯誤碼 {0}）", Kernel32.INSTANCE.GetLastError());
                }
                return true;
            }, null);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
            LOG.log(Level.WARNING, "截圖保護無法使用：{0}", e.getClass().getName());
        }
    }
}
