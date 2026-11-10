package app.svault.core.session;

import app.svault.core.storage.Settings;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 複製密碼後清除剪貼簿（T-19；US-05）：兩平台共用「什麼時候清」，平台的 {@link ClipboardPort} 決定「怎麼清」。
 * <ul>
 *   <li>複製密碼時依設定的秒數（{@link Settings#clipboardClearSeconds()}，10–300、預設 30，D-122）排定清除（AC-05-1～3）；
 *       每次複製都讀當下的設定，設定頁改了下一次複製就生效。</li>
 *   <li>再由 S-Vault 複製別的內容時，密碼已經不在剪貼簿：取消計時，不清掉新複製的內容。</li>
 *   <li>上鎖時由平台呼叫 {@link #clearNow()}，還沒清掉的密碼立即清除（AC-05-4）。android 因切到背景而上鎖時不呼叫
 *       （否則密碼貼不到別的 App），照原本的秒數清除（T-19 使用者決定）。</li>
 * </ul>
 * 只在平台的 UI 執行緒使用（不做同步）。
 *
 * <p>計時期間要留著密碼的參照（desktop 清除前要比對內容）。Java 字串無法清零，清除或取消後立刻丟掉參照（Architecture
 * §10「記憶體」）；這個類別不覆寫 {@code toString()}，也不記錄日誌。
 */
public final class ClipboardGuard {

    /** 平台的計時器（desktop 用 JavaFX 的 {@code PauseTransition}、android 用主執行緒的 {@code Handler}）。 */
    @FunctionalInterface
    public interface Scheduler {

        /**
         * 在 UI 執行緒上延後執行。
         *
         * @param delay 延後多久
         * @param task  要做的事
         * @return 取消用的動作（已執行或已取消時呼叫不做事）
         */
        Runnable schedule(Duration delay, Runnable task);
    }

    /** 平台清除失敗時，連同第一次最多試幾次（C-6）。 */
    public static final int MAX_CLEAR_ATTEMPTS = 3;

    /** 清除失敗後隔多久重試（C-6）。 */
    public static final Duration RETRY_DELAY = Duration.ofSeconds(1);

    private final ClipboardPort port;
    private final Scheduler scheduler;
    private final Supplier<Settings> settings;
    /** 還沒清掉的密碼；沒有時為 {@code null}。 */
    private String pending;
    private Runnable cancel;
    /** 每次複製加一：取消沒能攔住、晚到的舊計時器不會清掉新的內容。 */
    private long generation;
    /** 這個密碼已經清除失敗幾次（重試期間才不是 0）。 */
    private int failures;

    /**
     * @param port      平台的剪貼簿
     * @param scheduler 平台的計時器
     * @param settings  目前的設定（通常是 {@code LiveSettings::current}）
     */
    public ClipboardGuard(ClipboardPort port, Scheduler scheduler, Supplier<Settings> settings) {
        this.port = Objects.requireNonNull(port, "port");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * 複製密碼（標成敏感內容），設定的秒數後清除。
     *
     * @param secret 密碼
     * @return 多久後清除（畫面的「已複製密碼，n 秒後清除」用同一個值，AC-05-1）
     */
    public Duration copySecret(String secret) {
        Duration after = Duration.ofSeconds(settings.get().clipboardClearSeconds());
        drop();
        port.copy(secret, true);
        pending = secret == null ? "" : secret;
        long mine = ++generation;
        cancel = scheduler.schedule(after, () -> {
            if (mine == generation) {
                clearNow();
            }
        });
        return after;
    }

    /**
     * 複製不需要清除的內容（帳號、網址、備註）。剪貼簿上原本的密碼已被取代，取消它的清除。
     *
     * @param value 內容
     */
    public void copy(String value) {
        drop();
        port.copy(value, false);
    }

    /** 還沒清掉的密碼立即清除（上鎖、程式結束；AC-05-4）；沒有時不做事，不動使用者自己複製的內容。 */
    public void clearNow() {
        String secret = pending;
        int tried = failures;
        drop();
        if (secret != null) {
            clear(secret, tried);
        }
    }

    // 平台清除暫時失敗（例如 Windows 剪貼簿正被別的程式開著）時隔 1 秒重試，最多 MAX_CLEAR_ATTEMPTS 次（C-6）。
    // 重試期間仍要留著密碼參照（desktop 要比對內容）；放棄、成功、或使用者又複製別的內容時立刻丟掉
    private void clear(String secret, int tried) {
        try {
            port.clearSecret(secret);
        } catch (RuntimeException e) {
            int attempts = tried + 1;
            if (attempts >= MAX_CLEAR_ATTEMPTS) {
                throw e;
            }
            pending = secret;
            failures = attempts;
            long mine = ++generation;
            cancel = scheduler.schedule(RETRY_DELAY, () -> {
                if (mine == generation) {
                    clearNow();
                }
            });
        }
    }

    /** @return 有 S-Vault 複製、還沒清掉的密碼 */
    public boolean hasPendingSecret() {
        return pending != null;
    }

    // 先丟掉參照與計時，再呼叫平台：平台清除失敗時只在有限的重試期間留著密碼（clear），不會一直留在記憶體
    private void drop() {
        generation++;
        pending = null;
        failures = 0;
        if (cancel != null) {
            Runnable c = cancel;
            cancel = null;
            c.run();
        }
    }
}
