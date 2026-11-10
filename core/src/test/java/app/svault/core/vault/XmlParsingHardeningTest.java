package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.linguafranca.pwdb.StreamFormat;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.linguafranca.pwdb.kdbx.jackson.JacksonSerializableDatabase;

/**
 * 保管庫 XML 的解析防護（Codex 待確認：外部實體、實體展開、極深巢狀）。用 KeePassJava2 開檔時同一個 XML 解析路徑
 * （{@code JacksonSerializableDatabase#load}）直接餵特製的 XML；加密與 HMAC 不在這裡測（那一層擋的是不知道主密碼的人，
 * 這裡假設對方知道主密碼，或使用者開了別人給的檔案）。
 */
class XmlParsingHardeningTest {

    @TempDir
    Path tmp;

    @Test
    @DisplayName("對照組：沒有 DOCTYPE 時換進去的名稱讀得到（確認下面的測試真的有放進名稱）")
    void controlNameIsParsed() throws Exception {
        org.junit.jupiter.api.Assertions.assertEquals("普通名稱", parse(withDoctype("", "普通名稱")).getName());
    }

    @Test
    @DisplayName("外部實體不展開：DOCTYPE 指到本機檔案時讀不到檔案內容")
    void externalEntityIsNotResolved() throws Exception {
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "XXE-MARKER");
        String xml = withDoctype("<!DOCTYPE KeePassFile [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>", "&x;");

        String name = parseNameOrNull(xml);

        assertFalse(name != null && name.contains("XXE-MARKER"), "外部實體被展開了");
    }

    @Test
    @DisplayName("實體炸彈（十億笑）不會展開到耗盡記憶體，很快就失敗或忽略")
    void entityExpansionBombIsHarmless() {
        StringBuilder dtd = new StringBuilder("<!DOCTYPE KeePassFile [<!ENTITY a0 \"lol\">");
        for (int i = 1; i <= 9; i++) {
            dtd.append("<!ENTITY a").append(i).append(" \"");
            for (int j = 0; j < 10; j++) {
                dtd.append("&a").append(i - 1).append(';');
            }
            dtd.append("\">");
        }
        dtd.append("]>");
        String xml = withDoctype(dtd.toString(), "&a9;");

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            String name = parseNameOrNull(xml);
            assertFalse(name != null && name.length() > 1_000_000, "實體被完整展開了");
        });
    }

    @Test
    @DisplayName("極深的群組巢狀丟一般例外（之後成為「檔案損壞」），不是 StackOverflowError")
    void deepNestingFailsCleanly() throws Exception {
        String base = baseXml();
        int at = base.indexOf("</Group>");
        StringBuilder deep = new StringBuilder();
        int depth = 50_000;
        for (int i = 0; i < depth; i++) {
            deep.append("<Group><Name>g</Name>");
        }
        for (int i = 0; i < depth; i++) {
            deep.append("</Group>");
        }
        String xml = base.substring(0, at) + deep + base.substring(at);

        assertThrows(Exception.class, () -> parse(xml));
    }

    /** 解析，回傳資料庫名稱；解析失敗時回傳 {@code null}（失敗也算安全）。 */
    private static String parseNameOrNull(String xml) {
        try {
            return parse(xml).getName();
        } catch (Exception e) {
            return null;
        }
    }

    private static JacksonDatabase parse(String xml) throws IOException {
        JacksonSerializableDatabase jsd = new JacksonSerializableDatabase();
        new StreamFormat.None().load(jsd, null, new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        return new JacksonDatabase(jsd.keePassFile, new StreamFormat.None());
    }

    /** 在宣告後面加上 DOCTYPE，資料庫名稱換成 {@code name}。 */
    private static String withDoctype(String doctype, String name) {
        String base;
        try {
            base = baseXml();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        int decl = base.indexOf("?>") + 2;
        String withName = base.replaceFirst("<DatabaseName>[^<]*</DatabaseName>", "<DatabaseName>" + name
                + "</DatabaseName>");
        return withName.substring(0, decl) + doctype + withName.substring(decl);
    }

    /** 一個正常保管庫的明文 XML（S-Vault 建立、存成不加密的格式）。 */
    private static String baseXml() throws IOException {
        VaultCodec codec = new VaultCodec(TestVaults.fastParams());
        try (Vault vault = codec.create("名稱", TestVaults.password())) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            vault.database().save(new StreamFormat.None(), null, out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
