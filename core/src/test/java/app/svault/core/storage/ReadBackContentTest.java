package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import app.svault.core.vault.EntryView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultTampering;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 讀回驗證要比對附件內容與歷史內容（C-3）：名稱與筆數相同、內容不同的序列化結果也要擋下（AC-09-3）。
 */
class ReadBackContentTest {

    private final VaultCodec codec = new VaultCodec();
    private final List<Vault> opened = new ArrayList<>();
    private Vault vault;
    private Vault tampered;

    @BeforeEach
    void setUp() throws Exception {
        byte[] fixture = TestVaults.fixture(TestVaults.KXC_ATTACHMENTS_FIELDS);
        vault = track(codec.open(fixture, TestVaults.password()));
        tampered = track(codec.openWithKeyOf(fixture, vault));
    }

    @AfterEach
    void closeAll() {
        opened.forEach(Vault::close);
    }

    @Test
    @DisplayName("沒有改動時讀回驗證通過")
    void identicalPasses() {
        assertDoesNotThrow(() -> ReadBack.verify(codec, codec.serialize(tampered), vault));
    }

    @Test
    @DisplayName("C-3：附件名稱不變、內容不同 → 讀回驗證失敗")
    void attachmentContentIsCompared() {
        EntryView withAttachment = vault.entries().stream().filter(e -> !e.attachmentNames().isEmpty())
                .findFirst().orElseThrow();
        VaultTampering.replaceAttachment(tampered, withAttachment.uuid(), withAttachment.attachmentNames().get(0),
                "被換掉的內容".getBytes(StandardCharsets.UTF_8));

        assertThrows(SaveVerificationException.class, () -> ReadBack.verify(codec, codec.serialize(tampered), vault));
    }

    @Test
    @DisplayName("C-3：歷史筆數與時間不變、某個歷史版本的密碼不同 → 讀回驗證失敗")
    void historyContentIsCompared() {
        EntryView withHistory = vault.entries().stream().filter(e -> e.historyCount() > 0).findFirst().orElseThrow();
        VaultTampering.changeHistoryPassword(tampered, withHistory.uuid(), 0, "被換掉的舊密碼");

        assertThrows(SaveVerificationException.class, () -> ReadBack.verify(codec, codec.serialize(tampered), vault));
    }

    private Vault track(Vault v) {
        opened.add(v);
        return v;
    }
}
