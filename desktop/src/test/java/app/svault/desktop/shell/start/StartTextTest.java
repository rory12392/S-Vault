package app.svault.desktop.shell.start;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.VaultIoException;
import app.svault.core.vault.CorruptVaultException;
import app.svault.core.vault.UnsupportedKdfException;
import app.svault.core.vault.WrongPasswordException;
import java.util.ResourceBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 失敗時的呈現方式與固定文字（依驗收條件逐字比對）。
 */
class StartTextTest {

    private static final ResourceBundle TEXT = ResourceBundle.getBundle("app.svault.desktop.shell.start.messages");

    @Test
    @DisplayName("AC-01-3：主密碼錯誤顯示在欄位下方「主密碼錯誤」")
    void wrongPassword() {
        OpenProblem p = OpenProblem.of(new WrongPasswordException());
        assertTrue(p.fieldError());
        assertEquals("主密碼錯誤", TEXT.getString(p.messageKey()));
    }

    @Test
    @DisplayName("AC-01-7：Argon2id 以 error Banner 顯示 PRD 指定的文字，不附重新選擇")
    void unsupportedKdf() {
        OpenProblem p = OpenProblem.of(new UnsupportedKdfException("Argon2id"));
        assertFalse(p.fieldError());
        assertFalse(p.chooseAgain());
        assertEquals("目前不支援這個檔案的加密設定（Argon2id），請在 KeePassXC 的資料庫設定改成 Argon2d",
                TEXT.getString(p.messageKey()));
    }

    @Test
    @DisplayName("C-4：成本過高的確認文字有預估秒數與倍數；不在解鎖畫面時（還原、背景同步）是不開啟的說明")
    void costlyKdf() {
        assertEquals("解鎖大約需要 8 秒（一般保管庫的 15 倍），期間無法操作。如果你沒有在 KeePassXC 調高過這個設定，"
                + "檔案可能被改過，建議取消。",
                java.text.MessageFormat.format(TEXT.getString("unlock.costly.body"), 8L, 15L));
        assertEquals("仍要開啟", TEXT.getString("unlock.costly.ok"));
        OpenProblem p = OpenProblem.of(new app.svault.core.vault.CostlyKdfException(15));
        assertFalse(p.chooseAgain());
        assertEquals("這個檔案的加密設定比目前開啟的高很多，可能被改過；S-Vault 沒有開啟它", TEXT.getString(p.messageKey()));
    }

    @Test
    @DisplayName("AC-06-4：讀不到的三種原因各自的文字，都附「重新選擇檔案」")
    void unreadable() {
        assertBanner(VaultIoException.Reason.NOT_FOUND, "找不到這個檔案，它可能已被移動或刪除。");
        assertBanner(VaultIoException.Reason.ACCESS_DENIED, "S-Vault 已無法存取這個檔案，請重新選擇。");
        assertBanner(VaultIoException.Reason.IO, "目前離線，且這台裝置沒有這個檔案的副本。");
        assertEquals("重新選擇檔案", TEXT.getString("open.chooseAgain"));
    }

    @Test
    @DisplayName("檔案損壞：error Banner「檔案可能在上次存檔時損壞」（從備份還原在 T-12）")
    void corrupt() {
        OpenProblem p = OpenProblem.of(new CorruptVaultException("測試"));
        assertFalse(p.fieldError());
        assertEquals("檔案可能在上次存檔時損壞", TEXT.getString(p.messageKey()));
    }

    @Test
    @DisplayName("AC-01-4、AC-01-5：建立保管庫的驗證文字逐字比對")
    void createTexts() {
        assertEquals("主密碼不能是空的", TEXT.getString("create.error.empty"));
        assertEquals("兩次輸入的主密碼不一樣", TEXT.getString("create.error.mismatch"));
        assertEquals("請勾選確認你了解忘記主密碼的後果", TEXT.getString("create.error.acknowledge"));
        assertEquals("我了解：忘記主密碼將無法復原資料", TEXT.getString("create.acknowledge"));
        assertEquals("這個主密碼很容易被猜到，建議加長或改用通行片語", TEXT.getString("create.weak"));
    }

    @Test
    @DisplayName("CreateForm 用到的每個文字鍵都存在")
    void createKeysExist() {
        for (String key : new String[] {"create.error.name", "create.error.location", "create.error.exists",
                "create.error.failed", "unlock.error.unknown"}) {
            assertFalse(TEXT.getString(key).isBlank(), key);
        }
    }

    private static void assertBanner(VaultIoException.Reason reason, String expected) {
        OpenProblem p = OpenProblem.of(new VaultIoException(reason, "測試"));
        assertFalse(p.fieldError());
        assertTrue(p.chooseAgain());
        assertEquals(expected, TEXT.getString(p.messageKey()));
    }
}
