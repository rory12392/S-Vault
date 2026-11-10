package app.svault.desktop.shell.settings;

import app.svault.core.shell.Notices;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;

/**
 * 關於頁的文字。
 */
final class AboutInfo {

    /**
     * jpackage 產生的啟動器帶入的系統屬性（{@code --app-version}，desktop/build.gradle.kts 的 {@code appVersion}）。
     * 用它而不是 jar manifest 的 {@code Implementation-Version}：manifest 要改 build.gradle.kts（共用熱點）才會寫入。
     */
    static final String VERSION_PROPERTY = "jpackage.app-version";

    private AboutInfo() {
    }

    /**
     * @param property {@link #VERSION_PROPERTY} 的值；{@code :desktop:run} 等開發執行時沒有
     * @param text     文字
     * @return 「版本 0.1.0」；沒有版本時「版本 開發版」
     */
    static String version(String property, ResourceBundle text) {
        String v = property == null || property.isBlank() ? text.getString("about.version.dev") : property.strip();
        return MessageFormat.format(text.getString("about.version"), v);
    }

    /**
     * @param item 開源元件
     * @param text 文字
     * @return 展開後的說明行：版本（有時）、授權、用途（有時）
     */
    static List<String> details(Notices.Item item, ResourceBundle text) {
        List<String> lines = new ArrayList<>();
        if (!item.version().isEmpty()) {
            lines.add(MessageFormat.format(text.getString("about.item.version"), item.version()));
        }
        lines.add(MessageFormat.format(text.getString("about.item.license"), item.license()));
        if (!item.purpose().isEmpty()) {
            lines.add(MessageFormat.format(text.getString("about.item.purpose"), item.purpose()));
        }
        return lines;
    }

    /**
     * @param item 開源元件
     * @return 授權全文；一列有兩種授權時依序接在一起
     */
    static String licenseText(Notices.Item item) {
        StringBuilder sb = new StringBuilder();
        for (String id : item.licenseIds()) {
            if (sb.length() > 0) {
                sb.append("\n\n――――――――\n\n");
            }
            sb.append(Notices.licenseText(id).strip());
        }
        return sb.toString();
    }
}
