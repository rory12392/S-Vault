package app.svault.desktop.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 樣式表之間的一致性：兩份主題定義同一組 token，其他樣式表只引用已定義的 token，
 * 每個圖示都有路徑。色值本身由 token 檢查與截圖驗證。
 */
class StylesheetTokensTest {

    /** 色彩 token（含 shadow）。 */
    private static final List<String> DESIGN_TOKENS = List.of("bg", "surface", "surface-alt", "fg", "fg-muted",
            "border", "border-strong", "primary", "on-primary", "primary-container", "on-primary-container",
            "danger", "on-danger", "danger-container", "on-danger-container", "success", "success-container",
            "on-success-container", "warning", "warning-container", "on-warning-container", "shadow");

    private static final Pattern DEFINED = Pattern.compile("(-sv-[a-z-]+)\\s*:");
    private static final Pattern USED = Pattern.compile("(-sv-[a-z-]+)(?![a-z-]*\\s*:)");

    @Test
    @DisplayName("淺色與深色主題定義同一組 token，並包含全部預期的 token")
    void themesDefineSameTokens() throws IOException {
        Set<String> light = defined(read("theme-light.css"));
        Set<String> dark = defined(read("theme-dark.css"));
        assertEquals(light, dark);
        Set<String> expected = new TreeSet<>();
        DESIGN_TOKENS.forEach(t -> expected.add("-sv-" + t));
        assertEquals(expected, light);
    }

    @Test
    @DisplayName("base.css、icons.css 只引用主題中定義過的 token")
    void onlyDefinedTokensAreUsed() throws IOException {
        Set<String> defined = defined(read("theme-light.css"));
        for (String sheet : List.of("base.css", "icons.css")) {
            Matcher m = USED.matcher(stripComments(read(sheet)));
            while (m.find()) {
                assertTrue(defined.contains(m.group(1)), sheet + " 引用了未定義的 token " + m.group(1));
            }
        }
    }

    @Test
    @DisplayName("每個 IconName 在 icons.css 都有路徑")
    void everyIconHasShape() throws IOException {
        String icons = read("icons.css");
        for (IconName name : IconName.values()) {
            assertTrue(icons.contains("." + name.styleClass() + " { -fx-shape:"), "缺少圖示 " + name);
        }
    }

    private static Set<String> defined(String css) {
        Set<String> out = new TreeSet<>();
        Matcher m = DEFINED.matcher(stripComments(css));
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    private static String stripComments(String css) {
        return css.replaceAll("(?s)/\\*.*?\\*/", "");
    }

    private static String read(String name) throws IOException {
        try (InputStream in = StylesheetTokensTest.class.getResourceAsStream("/app/svault/desktop/ui/" + name)) {
            assertTrue(in != null, "找不到 " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
