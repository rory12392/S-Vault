package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.LocalStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * --self-check 的結果檔與結束碼（打包後的實際執行在完成的定義第 5 點）。
 */
class SelfCheckTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("開發環境：全部通過、結束碼 0、selfcheck.log 列出每項結果")
    void passesAndWritesLog() throws IOException {
        LocalStore store = LocalStore.open(tmp, Map.<String, String>of()::get, Map.<String, String>of()::get);
        int code = SelfCheck.run(store, Clock.fixed(Instant.parse("2026-10-17T00:00:00Z"), ZoneOffset.UTC));
        assertEquals(SelfCheck.OK, code);
        String log = new String(Files.readAllBytes(tmp.resolve("selfcheck.log")), StandardCharsets.UTF_8);
        assertTrue(log.contains("2026-10-17T00:00:00Z"), log);
        assertTrue(log.contains("OK   StAX 讀取（Woodstox）"), log);
        assertTrue(log.contains("OK   Argon2（Bouncy Castle）"), log);
        assertTrue(log.contains("結果 通過"), log);
    }
}
