package app.svault.core.security;

/**
 * 密碼強度的三個等級（StrengthMeter：弱 danger、中 warning、強 success，一律附文字）。
 */
public enum PasswordStrength {
    /** 弱：zxcvbn 分數 0–2（約 10^8 次猜測以內，擋不住保管庫檔案外流後的離線破解）。 */
    WEAK,
    /** 中：zxcvbn 分數 3。 */
    MEDIUM,
    /** 強：zxcvbn 分數 4。 */
    STRONG
}
