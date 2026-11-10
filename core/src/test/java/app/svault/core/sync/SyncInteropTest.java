package app.svault.core.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MemoryLocation;
import app.svault.core.storage.MutableClock;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AC-07-8：外部修改來自 KeePassXC 時，和來自 S-Vault 用同一套規則合併。用真的 {@code keepassxc-cli} 改遠端檔案
 * （KeePassXC 存檔會換 KDF 的鹽，S-Vault 解開遠端要重算一次 KDF）。
 *
 * <p>只在找得到 {@code keepassxc-cli} 時執行（使用者的電腦，SETUP S-06）；CI 沒有 KeePassXC，會略過。可用環境變數
 * {@code KEEPASSXC_CLI} 指定完整路徑。暫存檔都在 JUnit 的暫存資料夾、只含假資料（密碼 probe1234）。
 */
class SyncInteropTest {

    private static String cli;

    @TempDir
    Path tmp;

    @BeforeAll
    static void findCli() {
        String configured = System.getenv("KEEPASSXC_CLI");
        cli = configured != null && !configured.trim().isEmpty() ? configured : "keepassxc-cli";
        boolean available;
        try {
            available = run(Arrays.asList(cli, "--version")) == 0;
        } catch (IOException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "找不到 keepassxc-cli，略過與 KeePassXC 的互通測試");
    }

    @Test
    @DisplayName("AC-07-8：KeePassXC 改了備註並新增條目、S-Vault 改了密碼 → 存檔時合併，兩邊都保留，KeePassXC 開得了結果")
    void keePassXcEditsAreMergedLikeAnyOther() throws Exception {
        VaultCodec codec = new VaultCodec(TestVaults.fastParams());
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T05:00:00Z"), ZoneOffset.UTC);
        MemoryLocation remote = new MemoryLocation("H:/S-Vault/互通.kdbx", TestVaults.newVaultBytes("互通"));
        SyncDevice pc = new SyncDevice(tmp.resolve("pc"), codec, remote, clock);
        Path file = tmp.resolve("remote.kdbx");
        pc.unlock();
        try {
            UUID id = pc.add("Shared", "old");
            byte[] beforeKeePassXc = remote.content();
            Files.write(file, beforeKeePassXc);

            assertEquals(0, cliOnFile(file, "edit", "--notes", "notes from KeePassXC", "Shared"));
            assertEquals(0, cliOnFile(file, "add", "-u", "kx", "FromKeePassXC"));
            remote.replace(Files.readAllBytes(file));

            SyncReport report = pc.editAndSave(e -> e.updateEntry(id, new EntryDraft("Shared", "rory", "new", "", "")));

            assertEquals(SyncReport.Status.MERGED, report.status());
            assertEquals(List.of("FromKeePassXC", "Shared"), pc.titles());
            EntryView shared = pc.entry("Shared");
            assertEquals("new", shared.password());
            assertEquals("notes from KeePassXC", shared.notes());
            Files.write(file, remote.content());
            assertEquals(0, cliOnFile(file, "ls"), "KeePassXC 開得了合併後的檔案");
            try (Vault reopened = codec.open(remote.content(), TestVaults.password())) {
                assertEquals(2, reopened.entries().size());
            }
            assertTrue(!Arrays.equals(beforeKeePassXc, remote.content()));
        } finally {
            pc.session.lock();
        }
    }

    // keepassxc-cli 從標準輸入讀主密碼；傳回結束碼
    private static int cliOnFile(Path file, String command, String... args) throws IOException {
        List<String> line = new ArrayList<>(Arrays.asList(cli, command, "-q", file.toString()));
        // 選項放在資料庫路徑後面、條目名稱前面：keepassxc-cli 依位置解析，選項放哪都可以
        line.addAll(Arrays.asList(args));
        return run(line, (TestVaults.PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static int run(List<String> command) throws IOException {
        return run(command, null);
    }

    private static int run(List<String> command, byte[] stdin) throws IOException {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin);
            }
        }
        try (InputStream out = process.getInputStream()) {
            out.transferTo(new ByteArrayOutputStream());
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return -1;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return process.exitValue();
    }
}
