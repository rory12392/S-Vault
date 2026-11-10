package app.svault.core.security;

import com.nulabinc.zxcvbn.Strength;
import com.nulabinc.zxcvbn.Zxcvbn;
import java.util.Collections;
import java.util.List;

/**
 * 密碼強度評估（Architecture §6）：建立保管庫的主密碼提示、密碼產生器（AC-04-4）與健檢的弱密碼
 * （AC-13-1）共用。以 zxcvbn4j 的分數換算三個等級：0–2 弱、3 中、4 強（2026-10-18 使用者決定）。
 *
 * <p>完全在本機計算（字典在函式庫的 jar 裡），不連網。建立時載入字典（約數百毫秒），呼叫端應重複使用
 * 同一個實例；方法是同步的，可以從不同執行緒呼叫。
 */
public final class StrengthEstimator {

    /**
     * 評估時只看前 64 個字元：zxcvbn 的比對時間隨長度快速增加（T-09 實測 PC 上 100 字元約 0.45 秒，
     * 手機更慢，產生器要即時顯示）。截斷只可能低估（前 64 字元比整串好猜），不會把弱密碼評成強；
     * 64 個字元的隨機密碼本來就遠超過「強」的門檻。
     */
    static final int MAX_EVALUATED_LENGTH = 64;

    /** zxcvbn 的「強」分數；分數範圍 0–4。 */
    private static final int SCORE_STRONG = 4;
    private static final int SCORE_MEDIUM = 3;

    private final Zxcvbn zxcvbn = new Zxcvbn();

    /**
     * @param password 要評估的密碼；不會被保留或記錄
     * @return 強度等級；空字串為弱
     */
    public PasswordStrength estimate(CharSequence password) {
        return estimate(password, Collections.emptyList());
    }

    /**
     * 帶上下文評估：密碼和上下文（例如條目名稱、帳號、保管庫名稱）相同或相近時會被降級。
     *
     * @param password 要評估的密碼；不會被保留或記錄
     * @param context  使用者相關的字詞（健檢用條目的名稱與帳號）；可以是空清單
     * @return 強度等級；空字串為弱
     */
    public synchronized PasswordStrength estimate(CharSequence password, List<String> context) {
        if (password.length() == 0) {
            return PasswordStrength.WEAK;
        }
        CharSequence evaluated = password.length() > MAX_EVALUATED_LENGTH
                ? password.subSequence(0, MAX_EVALUATED_LENGTH) : password;
        Strength strength = zxcvbn.measure(evaluated, context);
        try {
            return fromScore(strength.getScore());
        } finally {
            // 函式庫的結果物件保留密碼與比對片段；清掉，不讓它們在堆積裡多留一份
            strength.wipe();
        }
    }

    /**
     * @param score zxcvbn 分數（0–4）
     * @return 對應的等級
     */
    static PasswordStrength fromScore(int score) {
        if (score >= SCORE_STRONG) {
            return PasswordStrength.STRONG;
        }
        return score == SCORE_MEDIUM ? PasswordStrength.MEDIUM : PasswordStrength.WEAK;
    }
}
