package app.svault.core.session;

import app.svault.core.storage.Settings;
import java.time.Duration;
import java.util.Optional;

/**
 * 自動上鎖的設定值換成時間長度（T-18；AC-12-1、AC-12-2）。範圍與預設值只定義在 {@link Settings}（T-23，D-122）。
 */
public final class AutoLock {

    private AutoLock() {
    }

    /**
     * @param settings 目前的設定
     * @return PC 閒置多久上鎖（1–60 分鐘，預設 5）；設成 0（不自動上鎖）時為空
     */
    public static Optional<Duration> desktopIdleLimit(Settings settings) {
        int minutes = settings.autoLockDesktopMinutes();
        return minutes == Settings.AUTO_LOCK_OFF ? Optional.empty() : Optional.of(Duration.ofMinutes(minutes));
    }

    /**
     * @param settings 目前的設定
     * @return Android 切到背景多久上鎖（0–3600 秒，預設 30；0＝立即）
     */
    public static Duration androidBackgroundLimit(Settings settings) {
        return Duration.ofSeconds(settings.autoLockAndroidSeconds());
    }
}
