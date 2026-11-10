package app.svault.core.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link StrengthEstimator}：zxcvbn 分數 0–2 弱、3 中、4 強（2026-10-18 使用者決定）。
 * 建立保管庫的強度提示、產生器（AC-04-4）、健檢的弱密碼（AC-13-1）共用。
 */
class StrengthEstimatorTest {

    private final StrengthEstimator estimator = new StrengthEstimator();

    @Test
    @DisplayName("分數對應：0–2 弱、3 中、4 強")
    void scoreMapping() {
        assertEquals(PasswordStrength.WEAK, StrengthEstimator.fromScore(0));
        assertEquals(PasswordStrength.WEAK, StrengthEstimator.fromScore(1));
        assertEquals(PasswordStrength.WEAK, StrengthEstimator.fromScore(2));
        assertEquals(PasswordStrength.MEDIUM, StrengthEstimator.fromScore(3));
        assertEquals(PasswordStrength.STRONG, StrengthEstimator.fromScore(4));
    }

    @Test
    @DisplayName("常見密碼、空字串、短密碼是弱")
    void commonPasswordsAreWeak() {
        for (String weak : List.of("", "password", "123456", "qwerty123", "iloveyou", "abc", "Tr0ub4dour&3")) {
            assertEquals(PasswordStrength.WEAK, estimator.estimate(weak), weak);
        }
    }

    @Test
    @DisplayName("長的隨機密碼與四個不相關單字的通行片語是強")
    void longRandomIsStrong() {
        assertEquals(PasswordStrength.STRONG, estimator.estimate("correct horse battery staple"));
        assertEquals(PasswordStrength.STRONG, estimator.estimate("g7#Kq!2vZp@9LmW$xR4t"));
    }

    @Test
    @DisplayName("中間等級存在：分數 3 的密碼評為中")
    void mediumExists() {
        assertEquals(PasswordStrength.MEDIUM, estimator.estimate("Kq7!vZp2x"));
    }

    @Test
    @DisplayName("和帳號、保管庫名稱等上下文相同的密碼被降級（健檢用條目的名稱與帳號當上下文）")
    void contextLowersStrength() {
        String password = "rorysvaultzq";
        PasswordStrength alone = estimator.estimate(password);
        PasswordStrength withContext = estimator.estimate(password, List.of("rorysvaultzq"));
        assertEquals(PasswordStrength.WEAK, withContext);
        assertTrue(withContext.compareTo(alone) <= 0);
    }

    @Test
    @DisplayName("產生器的上限 128 字元也能即時評估（AC-04-4 即時顯示）")
    void longPasswordsAreFast() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 128; i++) {
            sb.append((char) ('!' + (i * 37) % 90));
        }
        // 用同樣長度的字串暖機（字典載入、JIT），再量 5 次取中位數；CI 的共用機器只量一次會超標（M-01 整合的 CI 第 1 次）
        for (int i = 0; i < 3; i++) {
            estimator.estimate(sb);
        }
        List<Long> ms = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            PasswordStrength s = estimator.estimate(sb);
            ms.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            assertEquals(PasswordStrength.STRONG, s);
        }
        List<Long> sorted = new ArrayList<>(ms);
        sorted.sort(null);
        long median = sorted.get(sorted.size() / 2);
        assertTrue(median < 200, "128 字元評估要在 200 ms 內（中位數）：各次 " + ms + " ms");
    }
}
