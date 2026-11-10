package app.svault.android.shell;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.time.Duration;

/**
 * 把 App 所有畫面的開始與停止轉給 {@link BackgroundLock}（T-18；AC-12-2），並以主執行緒的 {@link Handler} 當計時器。
 * 不用 {@code ProcessLifecycleOwner}（要多一個 lifecycle-process 相依，T-14 同樣的決定）。
 */
public final class AutoLockCallbacks implements Application.ActivityLifecycleCallbacks, BackgroundLock.Timer {

    private final Handler main = new Handler(Looper.getMainLooper());
    private BackgroundLock lock;
    private Runnable pending;

    /**
     * @param lock 判斷（建好後呼叫一次）
     */
    public void attach(BackgroundLock lock) {
        this.lock = lock;
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        if (lock != null) {
            lock.activityStarted();
        }
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        if (lock != null) {
            lock.activityStopped(activity.isChangingConfigurations(), activity.isFinishing());
        }
    }

    @Override
    public void schedule(Runnable task, Duration delay) {
        cancel();
        pending = task;
        main.postDelayed(task, delay.toMillis());
    }

    @Override
    public void cancel() {
        if (pending != null) {
            main.removeCallbacks(pending);
            pending = null;
        }
    }

    @Override
    public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle savedInstanceState) {
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
    }

    @Override
    public void onActivityPaused(@NonNull Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(@NonNull Activity activity) {
    }
}
