package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * 暫存檔＋讀回比對＋原子取代（AC-09-3 的檔案層）。
 */
class AtomicFilesTest {

    @TempDir
    Path tmp;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("新建與取代檔案，不留下暫存檔")
    void writesAndReplaces() throws IOException {
        Path target = tmp.resolve("a.kdbx");
        AtomicFiles.write(target, bytes("第一版"));
        assertArrayEquals(bytes("第一版"), Files.readAllBytes(target));
        AtomicFiles.write(target, bytes("第二版"));
        assertArrayEquals(bytes("第二版"), Files.readAllBytes(target));
        assertFalse(Files.exists(AtomicFiles.tempFileOf(target)));
    }

    @ParameterizedTest
    @EnumSource(AtomicFiles.Stage.class)
    @DisplayName("AC-09-3：任一步中斷 → 原檔不變、暫存檔清掉")
    void faultAtEveryStageKeepsOriginal(AtomicFiles.Stage failAt) throws IOException {
        Path target = tmp.resolve("b.kdbx");
        Files.write(target, bytes("原檔"));
        byte[] big = new byte[256 * 1024];
        assertThrows(IOException.class, () -> AtomicFiles.write(target, big, stage -> {
            if (stage == failAt) {
                throw new IOException("模擬中斷：" + stage);
            }
        }));
        assertArrayEquals(bytes("原檔"), Files.readAllBytes(target));
        assertFalse(Files.exists(AtomicFiles.tempFileOf(target)));
    }

    @Test
    @DisplayName("上次中斷留下的暫存檔不影響下一次寫入")
    void leftoverTempIsReplaced() throws IOException {
        Path target = tmp.resolve("c.kdbx");
        Files.write(AtomicFiles.tempFileOf(target), bytes("上次留下的半個檔"));
        AtomicFiles.write(target, bytes("新內容"));
        assertArrayEquals(bytes("新內容"), Files.readAllBytes(target));
        assertFalse(Files.exists(AtomicFiles.tempFileOf(target)));
    }

    @Test
    @DisplayName("資料夾不存在時自動建立")
    void createsParent() throws IOException {
        Path target = tmp.resolve("x").resolve("y").resolve("d.json");
        AtomicFiles.write(target, bytes("{}"));
        assertArrayEquals(bytes("{}"), Files.readAllBytes(target));
    }
}
