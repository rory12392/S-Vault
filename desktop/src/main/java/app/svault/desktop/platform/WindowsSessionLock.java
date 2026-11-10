package app.svault.desktop.platform;

import app.svault.core.session.LockSignalPort;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HINSTANCE;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinUser;
import com.sun.jna.platform.win32.Wtsapi32;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Windows 鎖定螢幕（{@code Win+L}、睡眠時鎖定、切換使用者）時立即上鎖（AC-12-1；Architecture §10）：以 JNA 建一個不顯示
 * 的視窗，{@code WTSRegisterSessionNotification} 註冊這個工作階段的通知，收到 {@code WTS_SESSION_LOCK} 時呼叫
 * {@link #start} 給的動作。視窗與訊息迴圈在自己的背景執行緒（daemon），動作也在那條執行緒上呼叫。
 *
 * <p>JNA 的授權選 Apache 2.0（NOTICES.md）。不是 Windows、或 JNA 載入失敗時 {@link #start} 回傳 {@code false}，
 * 閒置上鎖照常有效。
 */
public final class WindowsSessionLock implements LockSignalPort {

    private static final Logger LOG = Logger.getLogger(WindowsSessionLock.class.getName());
    /** {@code WM_WTSSESSION_CHANGE}（WinUser.h；jna-platform 沒有這個常數）。 */
    private static final int WM_WTSSESSION_CHANGE = 0x02B1;
    private static final String WINDOW_CLASS = "SVaultSessionWatch";
    private static final long START_TIMEOUT_SECONDS = 5;

    private volatile HWND window;
    private volatile Runnable onSignal;
    private Thread thread;
    // 原生程式持有回呼的指標：要留著參照，不然被回收後 Windows 呼叫到已釋放的記憶體
    private WinUser.WindowProc proc;

    /**
     * @return Windows 上是這個類別，其他平台不提供訊號
     */
    public static LockSignalPort forCurrentPlatform() {
        return Platform.isWindows() ? new WindowsSessionLock() : LockSignalPort.NONE;
    }

    /**
     * 自我檢查用（{@code --self-check}）：JNA 的原生程式庫在這個執行環境載入得了（打包後 jar 與原生檔都在）。
     *
     * @return 是 Windows 而且呼叫得到 {@code kernel32}
     */
    public static boolean nativeLoads() {
        try {
            return Platform.isWindows() && Kernel32.INSTANCE.GetCurrentProcessId() > 0;
        } catch (LinkageError | RuntimeException e) {
            LOG.log(Level.WARNING, "JNA 載入失敗：{0}", e.getClass().getName());
            return false;
        }
    }

    @Override
    public synchronized boolean start(Runnable action) {
        if (thread != null) {
            return window != null;
        }
        onSignal = action;
        CountDownLatch ready = new CountDownLatch(1);
        thread = new Thread(() -> run(ready), "svault-session-watch");
        thread.setDaemon(true);
        thread.start();
        try {
            ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return window != null;
    }

    @Override
    public synchronized void stop() {
        HWND w = window;
        if (w != null) {
            // 在視窗自己的執行緒上取消註冊並關掉（DestroyWindow 只能由建立視窗的執行緒呼叫）
            User32.INSTANCE.PostMessage(w, WinUser.WM_CLOSE, new WPARAM(0), new LPARAM(0));
        }
        onSignal = null;
        thread = null;
    }

    /**
     * 開發版畫面腳本用：送一個「工作階段已鎖定」的訊息給自己的視窗，走和真的鎖定相同的路徑（不真的鎖定使用者的電腦）。
     *
     * @return 已送出（{@link #start} 成功過）
     */
    public boolean simulateLock() {
        HWND w = window;
        if (w == null) {
            return false;
        }
        User32.INSTANCE.PostMessage(w, WM_WTSSESSION_CHANGE, new WPARAM(Wtsapi32.WTS_SESSION_LOCK), new LPARAM(0));
        return true;
    }

    private void run(CountDownLatch ready) {
        HINSTANCE instance;
        HWND created = null;
        try {
            instance = Kernel32.INSTANCE.GetModuleHandle(null);
            proc = this::windowProc;
            WinUser.WNDCLASSEX wc = new WinUser.WNDCLASSEX();
            wc.hInstance = instance;
            wc.lpfnWndProc = proc;
            wc.lpszClassName = WINDOW_CLASS;
            User32.INSTANCE.RegisterClassEx(wc);
            // 一般的頂層視窗但不顯示（不用 HWND_MESSAGE：訊息專用視窗收不到部分系統通知）
            created = User32.INSTANCE.CreateWindowEx(0, WINDOW_CLASS, WINDOW_CLASS, 0, 0, 0, 0, 0, null, null,
                    instance, null);
            if (created == null) {
                LOG.log(Level.WARNING, "無法建立 Windows 鎖定偵測視窗：錯誤碼 {0}", Kernel32.INSTANCE.GetLastError());
                return;
            }
            if (!Wtsapi32.INSTANCE.WTSRegisterSessionNotification(created, Wtsapi32.NOTIFY_FOR_THIS_SESSION)) {
                LOG.log(Level.WARNING, "無法註冊 Windows 鎖定通知：錯誤碼 {0}", Kernel32.INSTANCE.GetLastError());
                User32.INSTANCE.DestroyWindow(created);
                return;
            }
            window = created;
        } catch (LinkageError | RuntimeException e) {
            // JNA 的原生程式庫載入失敗等：只記類型，程式照常運作
            LOG.log(Level.WARNING, "Windows 鎖定偵測無法啟動：{0}", e.getClass().getName());
            return;
        } finally {
            ready.countDown();
        }
        WinUser.MSG msg = new WinUser.MSG();
        while (User32.INSTANCE.GetMessage(msg, null, 0, 0) > 0) {
            User32.INSTANCE.TranslateMessage(msg);
            User32.INSTANCE.DispatchMessage(msg);
        }
        User32.INSTANCE.UnregisterClass(WINDOW_CLASS, instance);
    }

    private LRESULT windowProc(HWND hwnd, int msg, WPARAM wParam, LPARAM lParam) {
        switch (msg) {
            case WM_WTSSESSION_CHANGE:
                // 只記事件代碼（7＝鎖定、8＝解除鎖定）：確認通知有沒有送到
                LOG.log(Level.INFO, "Windows 工作階段變化：{0}", wParam.intValue());
                if (wParam.intValue() == Wtsapi32.WTS_SESSION_LOCK) {
                    fire();
                }
                return new LRESULT(0);
            case WinUser.WM_CLOSE:
                Wtsapi32.INSTANCE.WTSUnRegisterSessionNotification(hwnd);
                window = null;
                User32.INSTANCE.DestroyWindow(hwnd);
                return new LRESULT(0);
            case WinUser.WM_DESTROY:
                User32.INSTANCE.PostQuitMessage(0);
                return new LRESULT(0);
            default:
                return User32.INSTANCE.DefWindowProc(hwnd, msg, wParam, lParam);
        }
    }

    private void fire() {
        Runnable action = onSignal;
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            // 例外不能傳回原生的視窗程序
            LOG.log(Level.WARNING, "鎖定通知的處理失敗：{0}", e.getClass().getName());
        }
    }
}
