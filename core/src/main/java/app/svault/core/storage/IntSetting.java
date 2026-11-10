package app.svault.core.storage;

/**
 * 有範圍的整數設定（{@code settings.json}，Architecture §2.2）。兩平台的設定畫面與讀設定的程式（自動上鎖、剪貼簿）
 * 共用同一份範圍與預設值。
 *
 * @param key          點分隔的鍵
 * @param min          最小值（含）
 * @param max          最大值（含）
 * @param defaultValue 沒有設定、或檔案裡的值不合法時的值
 */
public record IntSetting(String key, int min, int max, int defaultValue) {

    /** 檢查範圍設定本身合理。 */
    public IntSetting {
        if (min > max || defaultValue < min || defaultValue > max) {
            throw new IllegalArgumentException("範圍不合理：" + key);
        }
    }

    /**
     * @param settings 設定
     * @return 設定值；沒有、型別不對或超出範圍（手改的檔案、別的版本寫的）時是預設值
     */
    public int read(Settings settings) {
        return settings.number(key)
                .filter(v -> v >= min && v <= max)
                .map(Long::intValue)
                .orElse(defaultValue);
    }

    /**
     * @param settings 原本的設定
     * @param value    新值
     * @return 改過的新設定（原物件不變）
     * @throws IllegalArgumentException 超出範圍（畫面應先限制輸入，這裡是最後一道檢查）
     */
    public Settings write(Settings settings, int value) {
        if (!accepts(value)) {
            throw new IllegalArgumentException(key + " 超出範圍 " + min + "–" + max + "：" + value);
        }
        return settings.with(key, (long) value);
    }

    /**
     * @param value 要檢查的值
     * @return 是否在範圍內
     */
    public boolean accepts(int value) {
        return value >= min && value <= max;
    }
}
