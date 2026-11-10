package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;

import app.svault.core.storage.MutableClock;
import app.svault.core.sync.MergePerf;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-07-7 效能測試用的 2,000 筆保管庫（{@code testFixtures/resources/kdbx/perf-2000.kdbx}，假資料，密碼 probe1234，
 * 測試用的低 KDF 參數）。附件要用 {@code core.vault} 的套件私有 API 才放得進去，儀器測試做不到（testFixtures 和 App
 * 不在同一個 class loader），所以在 JVM 上產生後放進 repo。
 *
 * <p>平常只檢查內容；設了環境變數 {@code SVAULT_WRITE_PERF_VAULT}（輸出路徑）時重新產生。
 */
class PerfVaultFixtureTest {

    @Test
    @DisplayName("AC-07-7 的效能測試檔：2,000 筆、10 個群組、100 筆各有 64 KB 附件、200 筆有歷史版本")
    void fixtureHasExpectedShape() throws Exception {
        String out = System.getenv("SVAULT_WRITE_PERF_VAULT");
        if (out != null && !out.trim().isEmpty()) {
            Files.write(Paths.get(out.trim()), generate());
        }
        try (Vault v = new VaultCodec().open(TestVaults.fixture(MergePerf.FIXTURE), TestVaults.password())) {
            List<EntryView> entries = v.entries();
            assertEquals(MergePerf.ENTRIES, entries.size());
            assertEquals(11, v.groups().size());
            assertEquals(100, entries.stream().filter(e -> !e.attachmentNames().isEmpty()).count());
            assertEquals(200, entries.stream().filter(e -> e.historyCount() > 0).count());
        }
    }

    private static byte[] generate() {
        VaultCodec codec = new VaultCodec(TestVaults.fastParams());
        MutableClock clock = new MutableClock(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
        try (Vault vault = codec.create("效能測試", TestVaults.password())) {
            VaultEditor editor = new VaultEditor(vault, clock);
            List<UUID> groups = new ArrayList<>();
            for (int g = 0; g < 10; g++) {
                groups.add(editor.addGroup(vault.id(), "群組 " + g));
            }
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < MergePerf.ENTRIES; i++) {
                String n = String.format("%04d", i);
                ids.add(editor.addEntry(groups.get(i % groups.size()), new EntryDraft("站台 " + n, "user" + n,
                        "pw-" + n + "-Xq7!", "https://site" + n + ".example.com/login",
                        "這是第 " + n + " 筆的備註，用來讓內容長度接近一般使用的保管庫。")));
            }
            clock.advance(Duration.ofDays(1));
            for (int i = 0; i < 200; i++) {
                String n = String.format("%04d", i);
                editor.updateEntry(ids.get(i * 10), new EntryDraft("站台 " + n, "user" + n, "pw2-" + n,
                        "https://site" + n + ".example.com/login", "改過一次密碼"));
            }
            for (int i = 0; i < 100; i++) {
                // 64 KB 有規律的內容：SHA-256 的時間和大小有關，和內容無關；有規律才壓縮得小，測試檔不會太大
                byte[] content = new byte[64 * 1024];
                for (int b = 0; b < content.length; b++) {
                    content[b] = (byte) ((b * 31 + i) & 0x7f);
                }
                KdbxCopy.putBinary(vault.database(), VaultTree.findEntry(vault.database().getRootGroup(),
                        ids.get(i * 20 + 1)), "附件-" + i + ".bin", content);
            }
            return codec.serialize(vault);
        }
    }
}
