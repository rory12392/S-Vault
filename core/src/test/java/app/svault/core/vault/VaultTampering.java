package app.svault.core.vault;

import java.util.UUID;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;

/**
 * 測試用：直接改開啟中保管庫的內部模型，模擬「序列化結果和記憶體不同、但解得開」的故障（C-3 的故障注入）。
 * 放在 {@code core.vault} 套件：只有這裡能碰 KeePassJava2 的模型。
 */
public final class VaultTampering {

    private VaultTampering() {
    }

    /**
     * 換掉附件內容，名稱不變。
     *
     * @param vault   開啟中的保管庫
     * @param entryId 條目
     * @param name    附件名稱（必須已存在）
     * @param content 新內容
     */
    public static void replaceAttachment(Vault vault, UUID entryId, String name, byte[] content) {
        JacksonEntry e = find(vault, entryId);
        KdbxCopy.removeBinary(e, name);
        KdbxCopy.putBinary(vault.database(), e, name, content);
    }

    /**
     * 改某個歷史版本的密碼，筆數與時間不變。
     *
     * @param vault    開啟中的保管庫
     * @param entryId  條目
     * @param index    歷史版本的位置（最舊的是 0）
     * @param password 新密碼
     */
    public static void changeHistoryPassword(Vault vault, UUID entryId, int index, String password) {
        JacksonEntry h = KdbxModelAccess.historyForWrite(find(vault, entryId)).get(index);
        // 歷史版本沒有連到資料庫，setPassword 需要資料庫的保護策略：暫時接上、改完拿掉
        try {
            java.lang.reflect.Field db = JacksonEntry.class.getDeclaredField("database");
            db.setAccessible(true);
            Object before = db.get(h);
            db.set(h, vault.database());
            h.setPassword(password);
            db.set(h, before);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JacksonEntry find(Vault vault, UUID entryId) {
        JacksonEntry e = VaultTree.findEntry(vault.database().getRootGroup(), entryId);
        if (e == null) {
            throw new IllegalArgumentException("沒有這個條目");
        }
        return e;
    }
}
