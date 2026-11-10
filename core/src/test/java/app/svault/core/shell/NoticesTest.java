package app.svault.core.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「關於／開源授權」頁的內容（T-23；T-24 的 android 共用）：來源是 {@code docs/NOTICES.md}（SOP 6.13 前在根目錄）。
 */
class NoticesTest {

    @Test
    @DisplayName("資源檔裡的 NOTICES.md 和 docs/ 那份相同（更新 NOTICES.md 時要一起複製）")
    void resourceMatchesRepositoryCopy() throws IOException {
        // Gradle 執行 core 測試時的工作目錄是 core/；SOP 6.13 起說明文件放 docs/
        Path root = Path.of("..", "docs", "NOTICES.md");
        String expected = new String(Files.readAllBytes(root), StandardCharsets.UTF_8);
        String actual;
        try (InputStream in = Notices.class.getResourceAsStream(Notices.RESOURCE)) {
            actual = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(expected, actual, "把 docs/NOTICES.md 複製到 core/src/main/resources" + Notices.RESOURCE);
    }

    @Test
    @DisplayName("PC 列出共用、Windows、圖示與字型；不列 Android 與不隨程式發佈的開發工具")
    void desktopSections() {
        List<String> titles = Notices.forPlatform(Notices.Platform.DESKTOP).stream()
                .map(Notices.Section::title).collect(Collectors.toList());
        assertEquals(List.of("兩平台共用（core）", "Windows（desktop）", "圖示與字型"), titles);
        List<String> android = Notices.forPlatform(Notices.Platform.ANDROID).stream()
                .map(Notices.Section::title).collect(Collectors.toList());
        assertEquals(List.of("兩平台共用（core）", "Android", "圖示與字型"), android);
    }

    @Test
    @DisplayName("每一列都有名稱、授權與至少一份授權全文，全文的資源檔都存在")
    void everyItemHasLicenseText() {
        for (Notices.Platform p : Notices.Platform.values()) {
            for (Notices.Section section : Notices.forPlatform(p)) {
                assertFalse(section.items().isEmpty(), section.title());
                for (Notices.Item item : section.items()) {
                    assertFalse(item.name().isBlank());
                    assertFalse(item.licenseIds().isEmpty(), "不認得的授權：" + item.license());
                    for (String id : item.licenseIds()) {
                        assertFalse(Notices.licenseText(id).isBlank(), id);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("Apache 套件的 NOTICE 與 GPL 元件的原始碼位置都在關於頁看得到（APK 打包時丟掉 jar 內的 NOTICE）")
    void noticesAndSourcesAreShown() {
        String apache = Notices.licenseText("apache-2.0");
        for (String notice : List.of("Apache Commons Codec", "Apache Geronimo STAX Spec 1.2", "Jackson JSON processor",
                "FastDoubleParser", "Schubfach")) {
            assertTrue(apache.contains(notice), notice);
        }
        String gpl = Notices.licenseText("gpl-2.0-classpath-exception");
        for (String source : List.of("github.com/adoptium/jdk25u", "github.com/openjdk/jfx",
                "github.com/google/desugar_jdk_libs")) {
            assertTrue(gpl.contains(source), source);
        }
    }

    @Test
    @DisplayName("解析表格：去掉 Markdown 記號；沒有版本欄的表格版本是空字串")
    void parsesRows() {
        Notices.Item first = Notices.forPlatform(Notices.Platform.DESKTOP).get(0).items().get(0);
        assertEquals("KeePassJava2（KeePassJava2-jackson、KeePassJava2-kdbx、database）", first.name());
        assertEquals("2.2.6", first.version());
        assertEquals("Apache License 2.0", first.license());
        assertEquals(List.of("apache-2.0"), first.licenseIds());

        Notices.Item icon = Notices.forPlatform(Notices.Platform.DESKTOP).get(2).items().get(0);
        assertEquals("", icon.version());
        assertTrue(icon.name().startsWith("Material Symbols"));
    }

    @Test
    @DisplayName("授權名稱對應全文：Bouncy Castle 不當成一般 MIT；一列可以有兩種授權")
    void licenseMapping() {
        assertEquals(List.of("bouncy-castle"), Notices.licenseIds("Bouncy Castle License（MIT 型）"));
        assertEquals(List.of("mit"), Notices.licenseIds("MIT License"));
        assertEquals(List.of("apache-2.0", "bsd-3-clause"),
                Notices.licenseIds("Apache License 2.0（JSR 305 為 BSD 3-Clause）"));
        assertEquals(List.of("bsd-2-clause"), Notices.licenseIds("BSD 2-Clause"));
        assertEquals(List.of("gpl-2.0-classpath-exception"), Notices.licenseIds("GPL v2 with Classpath Exception"));
        assertEquals(List.of("cc-by-3.0"), Notices.licenseIds("CC BY 3.0"));
        assertEquals(List.of("mit", "bsd-2-clause", "boost-1.0"),
                Notices.licenseIds("MIT License（內含 BSD 2-Clause、Boost Software License 1.0 的部分）"));
        assertEquals(List.of(), Notices.licenseIds("不知名授權"));
        assertThrows(IllegalArgumentException.class, () -> Notices.licenseText("../secret"));
    }
}
