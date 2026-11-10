package app.svault.core.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.linguafranca.pwdb.Credentials;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.StreamFormat;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonEntry;
import org.linguafranca.pwdb.kdbx.jackson.JacksonGroup;

/**
 * 測試用：直接透過函式庫新增或修改條目。正式的寫入 API（VaultEditor）在 T-09，這裡只為了準備測試資料。
 */
final class TestEntries {

    private TestEntries() {
    }

    /**
     * 在根群組新增一筆條目。密碼用函式庫的保護策略寫入，和 KeePassXC 一樣以 Protected 儲存。
     */
    static void add(Vault vault, String title, String username, String password, String url, String notes) {
        JacksonDatabase db = vault.database();
        JacksonGroup root = db.getRootGroup();
        JacksonEntry entry = db.newEntry();
        set(db, entry, Entry.STANDARD_PROPERTY_NAME_TITLE, title);
        set(db, entry, Entry.STANDARD_PROPERTY_NAME_USER_NAME, username);
        set(db, entry, Entry.STANDARD_PROPERTY_NAME_PASSWORD, password);
        set(db, entry, Entry.STANDARD_PROPERTY_NAME_URL, url);
        set(db, entry, Entry.STANDARD_PROPERTY_NAME_NOTES, notes);
        root.addEntry(entry);
    }

    /** 修改第一筆條目的備註，模擬「S-Vault 修改後存檔」（AC-01-2）。 */
    static void touchFirstEntryNotes(Vault vault, String notes) {
        JacksonDatabase db = vault.database();
        JacksonEntry first = db.findEntries(e -> true).get(0);
        set(db, first, Entry.STANDARD_PROPERTY_NAME_NOTES, notes);
    }

    private static void set(JacksonDatabase db, JacksonEntry entry, String name, String value) {
        entry.setPropertyValue(name, db.getPropertyValueStrategy().getFactoryFor(name).of(value));
    }

    /**
     * 以函式庫的明文 XML（不加密）輸出整個資料庫，用來比對存檔前後「函式庫讀到的一切」是否相同。
     * 只在測試的記憶體中使用，不寫檔。
     */
    static String plainXml(Vault vault) {
        Credentials none = () -> new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            vault.database().save(new StreamFormat.None(), none, out);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
