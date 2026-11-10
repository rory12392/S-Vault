package app.svault.android.feature.generator;

import android.content.Context;
import androidx.lifecycle.ViewModel;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.feature.generator.GeneratorSettings;
import app.svault.core.feature.generator.PasswordGenerator;
import app.svault.core.module.ModulePreferences;
import app.svault.core.security.PasswordStrength;
import app.svault.core.security.StrengthEstimator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 產生器的狀態。產生器頁用 Activity 範圍（CONVENTIONS §11）：旋轉、切換深色模式時外殼重新建立
 * {@link GeneratorPageFragment}，結果與選項留在這裡；頁面所在的 Activity 結束（返回、上鎖）時 {@link #onCleared()}
 * 清掉結果。編輯畫面的 {@link GeneratorSheet} 用 sheet 自己的範圍（T-44）：重建時 sheet 不還原，關閉時就清掉。
 *
 * <p>強度用核心的 {@link StrengthEstimator}：整個模組共用一個，第一次需要時在背景執行緒建立（要載入字典）。
 */
public final class GeneratorViewModel extends ViewModel {

    private static final PasswordGenerator GENERATOR = new PasswordGenerator();
    private static final Object ESTIMATOR_LOCK = new Object();
    private static volatile StrengthEstimator estimator;

    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "svault-generator-strength");
        // 不擋行程結束
        t.setDaemon(true);
        return t;
    });
    private GeneratorModel model;

    /** 給 {@code ViewModelProvider} 建立實例。 */
    public GeneratorViewModel() {
        // 選項在第一次取 model 時才讀（要 Context）
    }

    /**
     * @param context 頁面的 Context（只用來取 {@link ModuleHost}，不保存）
     * @return 這個頁面的狀態；第一次呼叫時讀上次的選項（{@code modules.generator.*}）並產生第一個結果
     */
    GeneratorModel model(Context context) {
        if (model == null) {
            ModulePreferences prefs = ModuleHost.of(context).preferences(GeneratorAndroidFeature.ID);
            model = new GeneratorModel(GeneratorSettings.load(prefs), GENERATOR, GeneratorViewModel::estimate,
                    background, context.getApplicationContext().getMainExecutor(), s -> s.save(prefs));
        }
        return model;
    }

    @Override
    protected void onCleared() {
        if (model != null) {
            // 同時拿掉監聽
            model.clear();
        }
        background.shutdownNow();
    }

    // 只在背景執行緒呼叫
    private static PasswordStrength estimate(CharSequence password) {
        StrengthEstimator e = estimator;
        if (e == null) {
            synchronized (ESTIMATOR_LOCK) {
                if (estimator == null) {
                    estimator = new StrengthEstimator();
                }
                e = estimator;
            }
        }
        return e.estimate(password);
    }
}
