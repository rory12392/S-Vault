package app.svault.desktop.shell.start;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.shell.Secrets;
import app.svault.core.storage.RecentVault;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 主密碼轉換、最近開啟列、Drive 資料夾。
 */
class StartHelpersTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("主密碼轉成 UTF-8 不經過 String（中文與符號正確），清零後全是 0")
    void secrets() {
        char[] chars = "密碼 pässwörd🔑".toCharArray();
        byte[] bytes = Secrets.utf8(chars);
        assertArrayEquals("密碼 pässwörd🔑".getBytes(StandardCharsets.UTF_8), bytes);
        Secrets.wipe(bytes);
        Secrets.wipe(chars);
        for (byte b : bytes) {
            assertEquals(0, b);
        }
        for (char c : chars) {
            assertEquals('\0', c);
        }
    }

    @Test
    @DisplayName("最近開啟列：名稱去掉 .kdbx、時間用裝置時區 yyyy/MM/dd HH:mm")
    void recentItem() {
        Path file = dir.resolve("個人").resolve("家裡的密碼.KDBX");
        RecentVault v = new RecentVault(file.toString(), "家裡的密碼.KDBX", UUID.randomUUID(),
                Instant.parse("2026-10-17T16:30:00Z"), "");
        RecentItem item = RecentItem.of(v, ZoneId.of("Asia/Taipei"));
        assertEquals("家裡的密碼", item.name());
        assertEquals("2026/10/18 00:30", item.lastOpened());
        assertEquals(file, item.file());
    }

    @Test
    @DisplayName("Drive 電腦版資料夾：找到第一個有「我的雲端硬碟」或 My Drive 的磁碟機")
    void driveFolders() throws IOException {
        Path g = Files.createDirectories(dir.resolve("G"));
        Path h = Files.createDirectories(dir.resolve("H").resolve("My Drive"));
        assertEquals(Optional.of(h), DriveFolders.find(List.of(g, dir.resolve("H"))));
        assertTrue(DriveFolders.find(List.of(g)).isEmpty());
    }
}
