package app.svault.core.feature.generator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import app.svault.core.security.PasswordStrength;
import app.svault.core.security.StrengthEstimator;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-04-4 的邏輯部分：產生的結果交給核心的 {@link StrengthEstimator} 評估，等級跟著選項變化。
 * 即時顯示的速度（128 字元 200 ms 內）由 {@code StrengthEstimatorTest} 驗證；畫面上的 StrengthMeter 屬 T-41／T-42。
 */
class GeneratorStrengthTest {

    private static final StrengthEstimator ESTIMATOR = new StrengthEstimator();
    private final PasswordGenerator generator = new PasswordGenerator();

    @Test
    @DisplayName("AC-04-4：預設選項產生的隨機密碼與通行片語評為強")
    void ac04_4_defaultsAreStrong() {
        // 3,000 次實測都是強（2026-10-22）；這裡跑 20 次
        for (int i = 0; i < 20; i++) {
            assertEquals(PasswordStrength.STRONG,
                    ESTIMATOR.estimate(new String(generator.password(GeneratorSettings.DEFAULTS.password()))));
            assertEquals(PasswordStrength.STRONG,
                    ESTIMATOR.estimate(new String(generator.passphrase(GeneratorSettings.DEFAULTS.passphrase()))));
        }
    }

    @Test
    @DisplayName("AC-04-4：選項變弱時強度跟著下降（8 位數字不是強）")
    void ac04_4_weakOptionsAreNotStrong() {
        PasswordOptions digits8 = new PasswordOptions(8, EnumSet.of(CharClass.DIGIT));
        for (int i = 0; i < 20; i++) {
            assertNotEquals(PasswordStrength.STRONG, ESTIMATOR.estimate(new String(generator.password(digits8))));
        }
    }
}
