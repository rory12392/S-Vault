package app.svault.core.storage;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * 執行中的設定（T-23）：設定畫面改了就立即生效，讀設定的程式（主題、T-18 自動上鎖、
 * T-19 剪貼簿）透過 {@link #current()} 取最新值或以 {@link Listener} 跟隨。
 *
 * <p>變更與寫檔分開：{@link #update} 只改記憶體並通知（可以在 UI 執行緒呼叫），{@link #save} 寫
 * {@code settings.json}（I/O，平台放到背景執行緒）。寫檔一律寫當下最新的內容，所以連續變更時晚到的寫入不會蓋回舊值。
 */
public final class LiveSettings {

    /** 設定變更的通知；在呼叫 {@link #update} 的執行緒上收到。 */
    @FunctionalInterface
    public interface Listener {

        /**
         * @param now 變更後的設定
         */
        void onChanged(Settings now);
    }

    private final SettingsStore store;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();
    private volatile Settings current;

    private LiveSettings(SettingsStore store, Settings initial) {
        this.store = store;
        this.current = Objects.requireNonNull(initial, "initial");
    }

    /**
     * @param store {@code settings.json}
     * @return 以檔案目前的內容開始（讀不到時是預設值，見 {@link SettingsStore#load()}）
     */
    public static LiveSettings load(SettingsStore store) {
        return new LiveSettings(Objects.requireNonNull(store, "store"), store.load());
    }

    /**
     * @param initial 初始設定
     * @return 只在記憶體、不寫檔（沒有本機資料夾時、測試）
     */
    public static LiveSettings inMemory(Settings initial) {
        return new LiveSettings(null, initial);
    }

    /** @return 目前的設定 */
    public Settings current() {
        return current;
    }

    /**
     * 改設定並通知監聽者；不寫檔（接著呼叫 {@link #save}）。
     *
     * @param change 從目前的設定算出新的設定（例：{@code s -> s.withTheme("dark")}）
     * @return 變更後的設定
     * @throws IllegalArgumentException {@code change} 給了超出範圍的值
     */
    public Settings update(UnaryOperator<Settings> change) {
        Settings now;
        synchronized (this) {
            now = Objects.requireNonNull(change.apply(current), "change");
            current = now;
        }
        for (Listener l : listeners) {
            l.onChanged(now);
        }
        return now;
    }

    /**
     * 把目前的設定寫進 {@code settings.json}（{@link AtomicFiles}，寫到一半中斷不會留下壞檔）。
     *
     * @throws IOException 寫入失敗（記憶體中的設定照常生效，下次變更再寫一次）
     */
    public void save() throws IOException {
        if (store == null) {
            return;
        }
        synchronized (writeLock) {
            store.save(current);
        }
    }

    /** @param listener 設定變更時通知 */
    public void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /** @param listener 不再通知 */
    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
