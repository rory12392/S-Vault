package app.svault.desktop.shell.settings;

/**
 * 設定畫面數字欄的輸入解讀（可直接打字的 {@code Spinner}）。
 */
final class SpinnerInput {

    private SpinnerInput() {
    }

    /**
     * @param typed   欄位裡的文字
     * @param current 目前的值（看不懂輸入時保留）
     * @param min     最小值
     * @param max     最大值
     * @return 要套用的值：不是整數時是 {@code current}，超出範圍時取最接近的邊界
     */
    static int parse(String typed, int current, int min, int max) {
        int value;
        try {
            value = Integer.parseInt(typed == null ? "" : typed.strip());
        } catch (NumberFormatException e) {
            return current;
        }
        return Math.max(min, Math.min(max, value));
    }
}
