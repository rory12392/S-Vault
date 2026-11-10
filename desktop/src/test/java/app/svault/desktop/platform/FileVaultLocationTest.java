package app.svault.desktop.platform;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.AtomicFiles;
import app.svault.core.storage.LocalStore;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PC 的保管庫位置：讀寫、原子取代與寫入途中強制中斷（AC-09-3）。
 */
class FileVaultLocationTest {

    @TempDir
    Path tmp;

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());

    private static byte[] newVaultBytes(String name) {
        return TestVaults.newVaultBytes(name);
    }

    @Test
    @DisplayName("讀寫、顯示名稱與位置字串")
    void readWrite() throws Exception {
        Path file = tmp.resolve("家用.kdbx");
        FileVaultLocation location = new FileVaultLocation(file);
        assertFalse(location.exists());
        assertEquals("家用.kdbx", location.displayName());
        assertEquals(file.toAbsolutePath().normalize().toString(), location.persistentId());
        assertTrue(location.supportsAtomicReplace());

        byte[] data = newVaultBytes("家用");
        location.writeAll(data);
        assertTrue(location.exists());
        assertArrayEquals(data, location.readAll());
        assertFalse(Files.exists(AtomicFiles.tempFileOf(file)));
    }

    @Test
    @DisplayName("AC-06-4：檔案不存在時回報 NOT_FOUND")
    void missingFile() {
        VaultIoException e = assertThrows(VaultIoException.class,
                () -> new FileVaultLocation(tmp.resolve("gone.kdbx")).readAll());
        assertEquals(VaultIoException.Reason.NOT_FOUND, e.reason());
    }

    @Test
    @DisplayName("AC-09-3：寫入途中失敗（行程內故障注入）→ 原檔不變")
    void inProcessFaultKeepsOriginal() throws Exception {
        Path file = tmp.resolve("v.kdbx");
        byte[] original = newVaultBytes("原檔");
        Files.write(file, original);
        FileVaultLocation location = new FileVaultLocation(file, stage -> {
            if (stage == AtomicFiles.Stage.VERIFIED) {
                throw new IOException("模擬在取代前中斷");
            }
        });
        VaultIoException e = assertThrows(VaultIoException.class, () -> location.writeAll(newVaultBytes("新")));
        assertEquals(VaultIoException.Reason.IO, e.reason());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    @DisplayName("AC-09-3：寫入途中強制結束行程，原檔仍可用原主密碼開啟；下次存檔正常")
    void killedProcessKeepsOriginal() throws Exception {
        Path file = tmp.resolve("家用.kdbx");
        byte[] original = newVaultBytes("家用");
        Files.write(file, original);
        // 夠大的新內容，確保暫存檔真的是「寫到一半」
        Path newContent = tmp.resolve("new.bin");
        Files.write(newContent, new byte[4 * 1024 * 1024]);
        Path marker = tmp.resolve("marker");

        Process child = new ProcessBuilder(javaExecutable(), "-cp", childClasspath(),
                InterruptedWriter.class.getName(), file.toString(), newContent.toString(), marker.toString())
                .redirectErrorStream(true)
                .redirectOutput(tmp.resolve("child.log").toFile())
                .start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Files.exists(marker)) {
                assertTrue(child.isAlive(), () -> "子行程提早結束：" + readLog());
                assertTrue(System.nanoTime() < deadline, "等不到子行程寫到一半");
                Thread.sleep(20);
            }
            assertTrue(Files.exists(AtomicFiles.tempFileOf(file)), "暫存檔寫到一半");
        } finally {
            child.destroyForcibly();
            assertTrue(child.waitFor(30, TimeUnit.SECONDS));
        }

        assertArrayEquals(original, Files.readAllBytes(file), "原檔一個位元組都沒變");
        try (Vault reopened = codec.open(Files.readAllBytes(file), TestVaults.password())) {
            assertEquals("家用", reopened.name());

            // 下一次正常存檔：清掉上次留下的暫存檔、成功取代
            LocalStore store = LocalStore.open(tmp.resolve("data"), k -> null, k -> null);
            FileVaultLocation location = new FileVaultLocation(file);
            new SaveCoordinator(codec, store, new RecentStore(store.recentFile()), Clock.systemUTC())
                    .save(reopened, location);
            assertFalse(Files.exists(AtomicFiles.tempFileOf(file)));
            try (Vault again = codec.open(location.readAll(), TestVaults.password())) {
                assertEquals(reopened.id(), again.id());
            }
        }
    }

    private String readLog() {
        try {
            return new String(Files.readAllBytes(tmp.resolve("child.log")), java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(讀不到子行程輸出)";
        }
    }

    private static String javaExecutable() {
        return Paths.get(System.getProperty("java.home"), "bin", "java").toString();
    }

    /** 子行程只需要 desktop 的正式與測試類別、core 的類別（不碰 JavaFX 與 KDBX 函式庫）。 */
    private static String childClasspath() throws URISyntaxException {
        List<String> parts = new ArrayList<>();
        for (Class<?> c : List.of(InterruptedWriter.class, FileVaultLocation.class, AtomicFiles.class)) {
            String p = Paths.get(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
            if (!parts.contains(p)) {
                parts.add(p);
            }
        }
        return String.join(File.pathSeparator, parts);
    }
}
