package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 本機資料夾的根目錄解析（{@code SVAULT_DATA_DIR}）與日誌（Architecture §2.2）。
 */
class LocalStoreTest {

    @TempDir
    Path tmp;

    @AfterEach
    void closeLog() {
        LogSetup.uninstall();
    }

    private static UnaryOperator<String> map(Map<String, String> values) {
        return values::get;
    }

    @Test
    @DisplayName("沒有覆寫時用平台預設")
    void usesPlatformDefault() {
        Path root = LocalStore.resolveRoot(tmp.resolve("default"), map(Map.of()), map(Map.of()));
        assertEquals(tmp.resolve("default").toAbsolutePath().normalize(), root);
    }

    @Test
    @DisplayName("環境變數 SVAULT_DATA_DIR 覆寫平台預設")
    void environmentOverrides() {
        Path root = LocalStore.resolveRoot(tmp.resolve("default"), map(Map.of()),
                map(Map.of(LocalStore.DATA_DIR_KEY, tmp.resolve("env").toString())));
        assertEquals(tmp.resolve("env"), root);
    }

    @Test
    @DisplayName("系統屬性優先於環境變數；空白值視為沒有設定")
    void systemPropertyWinsAndBlankIgnored() {
        Path env = tmp.resolve("env");
        assertEquals(tmp.resolve("prop"), LocalStore.resolveRoot(tmp.resolve("default"),
                map(Map.of(LocalStore.DATA_DIR_KEY, tmp.resolve("prop").toString())),
                map(Map.of(LocalStore.DATA_DIR_KEY, env.toString()))));
        assertEquals(env, LocalStore.resolveRoot(tmp.resolve("default"),
                map(Map.of(LocalStore.DATA_DIR_KEY, "  ")),
                map(Map.of(LocalStore.DATA_DIR_KEY, env.toString()))));
    }

    @Test
    @DisplayName("開啟時建立根目錄")
    void createsRoot() throws IOException {
        Path target = tmp.resolve("a").resolve("b");
        LocalStore store = LocalStore.open(tmp.resolve("unused"), map(Map.of()),
                map(Map.of(LocalStore.DATA_DIR_KEY, target.toString())));
        assertEquals(target, store.root());
        assertTrue(Files.isDirectory(target));
    }

    @Test
    @DisplayName("日誌寫到 logs/svault0.log（UTF-8）")
    void writesLogFile() throws IOException {
        LocalStore store = LocalStore.open(tmp, map(Map.of()), map(Map.of()));
        LogSetup.install(store);
        Logger.getLogger("app.svault.test").info("日誌測試");
        LogSetup.uninstall();

        Path log = store.logsDir().resolve("svault0.log");
        assertTrue(Files.exists(log));
        String content = new String(Files.readAllBytes(log), StandardCharsets.UTF_8);
        assertTrue(content.contains("日誌測試"), content);
        assertTrue(content.contains("app.svault.test"), content);
    }

    @Test
    @DisplayName("日誌輪替：每個 1 MB、最多 5 個")
    void rotation() throws IOException {
        LocalStore store = LocalStore.open(tmp, map(Map.of()), map(Map.of()));
        LogSetup.install(store);
        Logger logger = Logger.getLogger("app.svault.test");
        String line = "x".repeat(1000);
        for (int i = 0; i < 7000; i++) {
            logger.info(line);
        }
        LogSetup.uninstall();
        try (Stream<Path> files = Files.list(store.logsDir())) {
            long logs = files.filter(p -> p.getFileName().toString().matches("svault\\d\\.log")).count();
            assertEquals(LogSetup.FILE_COUNT, logs);
        }
        try (Stream<Path> files = Files.list(store.logsDir())) {
            assertTrue(files.allMatch(p -> p.toFile().length() <= LogSetup.FILE_LIMIT_BYTES + 2048));
        }
    }
}
