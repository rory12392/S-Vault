package app.svault.android.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 色彩 token 的一致性：淺色與深色定義同一組顏色、色值和 desktop 的主題相同，主題與元件只引用已定義的 {@code sv_*}
 * 顏色。工作目錄是 android 子專案（Gradle 單元測試的預設）。
 */
public class SvColorsTest {

    private static final Path RES = Paths.get("src", "main", "res");
    private static final Path THEMES = Paths.get("..", "desktop", "src", "main", "resources", "app", "svault", "desktop",
            "ui");

    private static final Pattern COLOR = Pattern.compile("<color name=\"(sv_[a-z_]+)\">(#[0-9A-Fa-f]{6,8})</color>");
    private static final Pattern USED = Pattern.compile("@color/(sv_[a-z_]+)");
    private static final Pattern CSS = Pattern.compile("-sv-([a-z-]+)\\s*:\\s*(#[0-9A-Fa-f]{6,8})\\s*;");

    @Test
    public void lightAndNightDefineSameColors() throws IOException {
        assertEquals(colors("values").keySet(), colors("values-night").keySet());
    }

    /**
     * 色值和 desktop 的主題 CSS 相同（兩平台同一組 token）。原本比對設計文件的色彩表；公開快照沒有那份文件，改比對
     * 一起匯出的 desktop 主題檔（設計文件的表與 desktop 主題由 desktop 的 token 檢查對齊）。
     */
    @Test
    public void colorsMatchDesktopTheme() throws IOException {
        Map<String, String> light = cssColors("theme-light.css");
        Map<String, String> dark = cssColors("theme-dark.css");
        Map<String, String> androidLight = withoutLauncher(colors("values"));
        assertEquals(22, androidLight.size());
        light.keySet().retainAll(androidLight.keySet());
        dark.keySet().retainAll(androidLight.keySet());
        assertEquals("desktop 主題要有 android 的每一個顏色", androidLight.keySet(), light.keySet());
        assertEquals(light, androidLight);
        assertEquals(dark, withoutLauncher(colors("values-night")));
    }

    @Test
    public void onlyDefinedColorsAreReferenced() throws IOException {
        Map<String, String> defined = colors("values");
        // res/color/ 的選擇器（依狀態切換的顏色，內容只引用主題屬性）也算已定義
        try (var selectors = Files.list(RES.resolve("color"))) {
            selectors.forEach(p -> defined.put(p.getFileName().toString().replace(".xml", ""), "selector"));
        }
        try (var files = Files.walk(RES)) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".xml"))::iterator) {
                Matcher m = USED.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) {
                    assertTrue(f + " 引用了未定義的顏色 " + m.group(1), defined.containsKey(m.group(1)));
                }
            }
        }
    }

    /** desktop 主題的 {@code -sv-名稱: #色值;}，轉成 android 的名稱與 #AARRGGBB（CSS 的 8 位數是 #RRGGBBAA）。 */
    private static Map<String, String> cssColors(String file) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Matcher m = CSS.matcher(Files.readString(THEMES.resolve(file), StandardCharsets.UTF_8));
        while (m.find()) {
            String hex = m.group(2).toUpperCase(Locale.ROOT);
            out.put("sv_" + m.group(1).replace('-', '_'), hex.length() == 9 ? cssToAndroid(hex) : hex);
        }
        return out;
    }

    /** App 圖示的顏色（{@code sv_launcher_*}，T-133）不是介面 token，desktop 主題沒有對應值。 */
    private static Map<String, String> withoutLauncher(Map<String, String> colors) {
        colors.keySet().removeIf(name -> name.startsWith("sv_launcher_"));
        return colors;
    }

    private static String cssToAndroid(String rrggbbaa) {
        return "#" + rrggbbaa.substring(7, 9) + rrggbbaa.substring(1, 7);
    }

    private static Map<String, String> colors(String dir) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Matcher m = COLOR.matcher(Files.readString(RES.resolve(dir).resolve("sv_colors.xml"), StandardCharsets.UTF_8));
        while (m.find()) {
            out.put(m.group(1), m.group(2).toUpperCase(Locale.ROOT));
        }
        return out;
    }
}
