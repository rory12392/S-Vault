package app.svault.android.shell.start;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * 畫面的一個背景工作（解鎖、建立、讀最近開啟清單）。放在 ViewModel 裡：工作進行中旋轉螢幕或切換深色模式
 * 重建 Activity 時，結果交給新的 Activity，不會丟掉，也不會重跑一次 KDF。一次只跑一個。
 */
public final class BackgroundJob extends ViewModel {

    /**
     * 工作的結果；{@code error} 不是 {@code null} 時表示失敗。
     *
     * @param tag   開始工作時給的標記（同一個畫面有不同工作時用來分辨）
     * @param value 回傳值
     * @param error 丟出的例外
     */
    public record Outcome(String tag, Object value, Exception error) { }

    private final MutableLiveData<Outcome> result = new MutableLiveData<>();
    private boolean running;
    private String runningTag;

    /** @return 有工作進行中（還沒交出結果）；重建後的畫面依此還原 loading 狀態 */
    public boolean isRunning() {
        return running;
    }

    /** @return 有工作進行中時，它的標記；否則 {@code null} */
    public String runningTag() {
        return running ? runningTag : null;
    }

    /**
     * @param worker 背景執行緒
     * @param tag    標記
     * @param work   工作；在背景執行緒執行，機密資料由它自己清零
     * @return 已開始；已有工作進行中時為 {@code false}（不排第二個）
     */
    public boolean start(Executor worker, String tag, Callable<?> work) {
        if (running) {
            return false;
        }
        running = true;
        runningTag = tag;
        worker.execute(() -> {
            Outcome o;
            try {
                o = new Outcome(tag, work.call(), null);
            } catch (Exception e) {
                o = new Outcome(tag, null, e);
            }
            result.postValue(o);
        });
        return true;
    }

    /** @return 結果；在主執行緒觀察，處理完呼叫 {@link #consume()} */
    public LiveData<Outcome> result() {
        return result;
    }

    /** 結果已處理：清掉，之後重建的畫面不會再處理一次。 */
    public void consume() {
        running = false;
        runningTag = null;
        result.setValue(null);
    }
}
