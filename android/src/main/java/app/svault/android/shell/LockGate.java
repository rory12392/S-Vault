package app.svault.android.shell;

import app.svault.core.session.VaultSession;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * 保管庫的上鎖狀態，給基底 Activity（{@link SecureActivity}）判斷要不要導回解鎖畫面。
 *
 * <p>狀態跟著 {@link VaultSession}（{@link #sessionListener}，由 {@code SvaultApplication} 註冊）；
 * T-18 的自動上鎖也透過 {@code VaultSession}。{@link #lock()}／{@link #markUnlocked()} 一律在 UI 執行緒呼叫。
 */
public final class LockGate {

    /** 上鎖時的通知。 */
    public interface Listener {

        /** 保管庫剛被上鎖；顯示保管庫內容的畫面應該離開。 */
        void onLocked();
    }

    // 通知中允許監聽者移除自己（Activity 收到通知後 finish），所以用寫入時複製的清單
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private boolean locked = true;

    /** @return 目前沒有解鎖的保管庫時為 {@code true}（啟動時就是上鎖狀態） */
    public boolean isLocked() {
        return locked;
    }

    /** 解鎖成功後呼叫。 */
    public void markUnlocked() {
        locked = false;
    }

    /** 上鎖；已經上鎖時不重複通知。 */
    public void lock() {
        if (locked) {
            return;
        }
        locked = true;
        for (Listener listener : listeners) {
            listener.onLocked();
        }
    }

    /**
     * 讓這個狀態跟著 {@link VaultSession}：解鎖、上鎖時呼叫 {@link #markUnlocked()}／{@link #lock()}。
     * {@code VaultSession} 在呼叫它的執行緒（常是背景執行緒）通知，所以轉到 {@code uiExecutor} 執行。
     *
     * @param uiExecutor 在 UI 執行緒執行的 Executor（正式程式是主執行緒的 {@code Handler::post}）
     * @return 要註冊到 {@link VaultSession#addListener} 的監聽者
     */
    public VaultSession.Listener sessionListener(Executor uiExecutor) {
        Objects.requireNonNull(uiExecutor, "uiExecutor");
        return new VaultSession.Listener() {
            @Override
            public void onUnlocked() {
                uiExecutor.execute(LockGate.this::markUnlocked);
            }

            @Override
            public void onLocked() {
                uiExecutor.execute(LockGate.this::lock);
            }
        };
    }

    /**
     * @param listener 上鎖時的通知對象
     */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * @param listener 不再通知的對象
     */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
