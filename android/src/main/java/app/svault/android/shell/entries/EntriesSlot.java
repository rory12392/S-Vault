package app.svault.android.shell.entries;

import androidx.annotation.Nullable;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 一次解鎖一份的條目狀態（T-31 發現的死結，M-03 整合修正）。
 *
 * <p>{@code VaultSession} 在自己的鎖裡通知上鎖，收到通知時 {@link #drop()} 要拿這裡的鎖，所以拿著這裡的鎖時<b>不能問 session</b>：
 * 否則主執行緒要內容、背景執行緒同時上鎖時兩邊互等（ANR）。{@link #get()} 因此在鎖外問；建立之後再問一次，
 * 兩次之間上鎖的話通知已經過了，由這裡丟掉，不留下屬於上一次解鎖的狀態。
 *
 * @param <T> 狀態的型別
 */
public final class EntriesSlot<T> {

    private final BooleanSupplier unlocked;
    private final Supplier<T> factory;
    private final Consumer<T> dispose;
    @Nullable private T current;

    /**
     * @param unlocked 保管庫目前是否解鎖（可能等 session 的鎖；不在這個物件的鎖裡呼叫）
     * @param factory  建立狀態（在這個物件的鎖裡呼叫，不能問 session）
     * @param dispose  丟掉狀態（不在這個物件的鎖裡呼叫）
     */
    public EntriesSlot(BooleanSupplier unlocked, Supplier<T> factory, Consumer<T> dispose) {
        this.unlocked = Objects.requireNonNull(unlocked, "unlocked");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.dispose = Objects.requireNonNull(dispose, "dispose");
    }

    /** @return 目前這次解鎖的狀態，需要時建立；沒有解鎖時為 {@code null} */
    @Nullable
    public T get() {
        boolean open = unlocked.getAsBoolean();
        T created = null;
        T result;
        synchronized (this) {
            if (current == null && open) {
                current = factory.get();
                created = current;
            }
            result = current;
        }
        if (created != null && !unlocked.getAsBoolean()) {
            drop();
            return null;
        }
        return result;
    }

    /** @return 目前的狀態，不建立；沒有時為 {@code null} */
    @Nullable
    public synchronized T peek() {
        return current;
    }

    /** 丟掉目前的狀態（上鎖、還原）；可以拿著 session 的鎖呼叫。 */
    public void drop() {
        T old;
        synchronized (this) {
            old = current;
            current = null;
        }
        if (old != null) {
            dispose.accept(old);
        }
    }
}
