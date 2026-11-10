package app.svault.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 單一 Java 檔 400 行上限（CONVENTIONS §10）：掃三個子專案 {@code src/} 底下所有 Java 檔（正式程式、測試、
 * testFixtures、儀器測試、debug 版）。原本靠里程碑檢視與自覺，T-12 時曾超過（P-45），M-02 整合改成機器檢查
 * （D-148），放在 core 的測試裡，CI 的 jvm job 也會跑。
 */
class FileLengthRulesTest {

    /** CONVENTIONS §10 的上限。 */
    static final int MAX_LINES = 400;

    private static final List<Path> ROOTS = List.of(
            Path.of("src"), Path.of("../desktop/src"), Path.of("../android/src"));

    @Test
    @DisplayName("CONVENTIONS §10：三個子專案的 Java 檔都不超過 400 行")
    void javaFilesStayWithinLineLimit() throws IOException {
        List<String> tooLong = new ArrayList<>();
        int scanned = 0;
        for (Path root : ROOTS) {
            assertTrue(Files.isDirectory(root), "找不到原始碼：" + root.toAbsolutePath());
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    scanned++;
                    long lines = lineCount(new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
                    if (lines > MAX_LINES) {
                        tooLong.add(f.normalize() + "：" + lines + " 行");
                    }
                }
            }
        }
        // 三個子專案合計數百個檔；太少表示路徑寫錯、掃了空資料夾
        assertTrue(scanned >= 300, "掃到的 Java 檔太少，可能是路徑寫錯：" + scanned);
        assertEquals(List.of(), tooLong, "超過 " + MAX_LINES + " 行的檔案要先拆（行為不變、測試通過）再加功能");
    }

    @Test
    @DisplayName("行數的算法：最後一行有沒有換行都算一行，空字串是 0 行")
    void countsLinesLikeWc() {
        assertEquals(0, lineCount(""));
        assertEquals(1, lineCount("a"));
        assertEquals(1, lineCount("a\n"));
        assertEquals(2, lineCount("a\nb"));
        assertEquals(3, lineCount("a\n\nb\n"));
        assertEquals(MAX_LINES + 1, lineCount("x\n".repeat(MAX_LINES + 1)));
    }

    static long lineCount(String source) {
        return source.lines().count();
    }
}
