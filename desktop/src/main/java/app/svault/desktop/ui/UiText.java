package app.svault.desktop.ui;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

/**
 * 共用元件的文字（{@code app/svault/desktop/ui/messages.properties}，UTF-8）。
 */
public final class UiText {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("app.svault.desktop.ui.messages");

    private UiText() {
    }

    /**
     * @param key 文字鍵
     * @return 對應文字
     * @throws java.util.MissingResourceException 鍵不存在時
     */
    public static String get(String key) {
        return BUNDLE.getString(key);
    }

    /**
     * @param key  文字鍵（內容用 {@link MessageFormat} 的 {@code {0}} 佔位）
     * @param args 代入的值
     * @return 代入後的文字
     */
    public static String format(String key, Object... args) {
        return new MessageFormat(get(key), Locale.TRADITIONAL_CHINESE).format(args);
    }
}
