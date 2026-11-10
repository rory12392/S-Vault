package app.svault.android.feature.health;

import android.content.Context;
import androidx.lifecycle.ViewModel;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.feature.health.BreachChecker;
import app.svault.core.feature.health.HealthReportModel;
import app.svault.core.feature.health.WeakReuseAnalyzer;
import app.svault.core.security.StrengthEstimator;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 健檢報告頁的狀態，Activity 範圍（CONVENTIONS §11）：旋轉、切換深色模式、改字體時外殼重新建立
 * {@link HealthReportFragment}，結果與進行中的外洩檢查留在這裡；頁面所在的 Activity 結束（返回、上鎖時整個工作換成解鎖畫面）
 * 時 {@link #onCleared()} 中斷外洩檢查並放掉結果。
 *
 * <p>弱／重複的分析器整個模組共用一個，第一次分析時在背景執行緒建立（要載入 zxcvbn 字典）。
 */
public final class HealthViewModel extends ViewModel {

    private static final Object ANALYZER_LOCK = new Object();
    private static volatile WeakReuseAnalyzer analyzer;
    /**
     * 外洩檢查用戶端。正式程式一律是 {@code BreachChecker::new}（官方位址；Android 上設不了 {@code SVAULT_HIBP_BASE_URL}）；
     * 只有儀器測試換成連不到的本機位址（不連真正的 HIBP，CONVENTIONS §5），測試結束時還原。
     */
    static volatile Supplier<BreachChecker> checkers = BreachChecker::new;

    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "svault-health");
        // 不擋行程結束
        t.setDaemon(true);
        return t;
    });
    private HealthReportModel model;
    /** 第一次開頁時開始分析；重建時不重算。 */
    private boolean started;

    /** 給 {@code ViewModelProvider} 建立實例。 */
    public HealthViewModel() {
        // 第一次取 model 時才接上 host（要 Context）
    }

    /**
     * @param context 頁面的 Context（只用來取 {@link ModuleHost}，不保存）
     * @return 這個頁面的狀態
     */
    HealthReportModel model(Context context) {
        if (model == null) {
            ModuleHost host = ModuleHost.of(context);
            Supplier<BreachChecker> checker = checkers;
            model = new HealthReportModel(host::unlockedEntries, HealthViewModel::analyzer, checker,
                    background, context.getApplicationContext().getMainExecutor(),
                    host.preferences(HealthAndroidFeature.ID), Clock.systemDefaultZone());
        }
        return model;
    }

    /** @return 第一次呼叫時為 true（之後的重建不重新分析） */
    boolean start() {
        if (started) {
            return false;
        }
        started = true;
        return true;
    }

    @Override
    protected void onCleared() {
        if (model != null) {
            model.close();
        }
        background.shutdownNow();
    }

    // 只在背景執行緒呼叫
    private static WeakReuseAnalyzer analyzer() {
        WeakReuseAnalyzer a = analyzer;
        if (a == null) {
            synchronized (ANALYZER_LOCK) {
                if (analyzer == null) {
                    analyzer = new WeakReuseAnalyzer(new StrengthEstimator());
                }
                a = analyzer;
            }
        }
        return a;
    }
}
