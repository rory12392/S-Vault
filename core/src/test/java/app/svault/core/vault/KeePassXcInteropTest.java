package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MutableClock;

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
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.linguafranca.pwdb.kdbx.Helpers;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 用真的 KeePassXC（{@code keepassxc-cli}）開啟 S-Vault 存的檔案（AC-01-1、AC-01-2）。
 *
 * <p>只在找得到 {@code keepassxc-cli} 時執行（使用者的電腦，SETUP S-06）；CI 沒有 KeePassXC，會略過。
 * 可用環境變數 {@code KEEPASSXC_CLI} 指定完整路徑。暫存檔都在 JUnit 的暫存資料夾、只含假資料。
 */
class KeePassXcInteropTest {

    static final List<String> FIXTURES = TestVaults.READABLE_KXC_FIXTURES;

    private static String cli;

    @TempDir
    Path tmp;

    @BeforeAll
    static void findCli() {
        String configured = System.getenv("KEEPASSXC_CLI");
        cli = configured != null && !configured.trim().isEmpty() ? configured : "keepassxc-cli";
        boolean available;
        try {
            available = run(Arrays.asList(cli, "--version"), null).exitCode == 0;
        } catch (IOException e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "找不到 keepassxc-cli，略過與 KeePassXC 的互通測試");
    }

    @Test
    @DisplayName("AC-01-1：S-Vault 建立的保管庫，KeePassXC 以相同主密碼開啟並看到相同的條目")
    void keePassXcOpensCreatedVault() throws Exception {
        VaultCodec codec = new VaultCodec();
        Path file = tmp.resolve("created.kdbx");
        try (Vault vault = codec.create("互通測試", TestVaults.password())) {
            TestEntries.add(vault, "GitHub", "rory", "s3cret-密碼", "https://github.com", "備註");
            TestEntries.add(vault, "銀行", "user01", "p@ss", "https://bank.example", "");
            Files.write(file, codec.serialize(vault));
        }
        Document xml = export(file);
        List<String> titles = new ArrayList<>();
        NodeList entries = xml.getElementsByTagName("Entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (entry.getParentNode().getNodeName().equals("History")) {
                continue;
            }
            titles.add(stringValue(entry, "Title"));
            if (stringValue(entry, "Title").equals("GitHub")) {
                assertEquals("rory", stringValue(entry, "UserName"));
                assertEquals("s3cret-密碼", stringValue(entry, "Password"));
                assertEquals("https://github.com", stringValue(entry, "URL"));
            }
        }
        Collections.sort(titles);
        assertEquals(Arrays.asList("GitHub", "銀行"), titles);
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("FIXTURES")
    @DisplayName("AC-01-2：S-Vault 修改並存檔後 KeePassXC 仍能開啟，除了被改的備註與修改時間外內容完全相同")
    void keePassXcSeesSameContentAfterSave(String fixture) throws Exception {
        Path original = tmp.resolve("original.kdbx");
        Path saved = tmp.resolve("saved.kdbx");
        Files.write(original, TestVaults.fixture(fixture));
        VaultCodec codec = new VaultCodec();
        try (Vault vault = codec.open(TestVaults.fixture(fixture), TestVaults.password())) {
            TestEntries.touchFirstEntryNotes(vault, "S-Vault 修改過");
            Files.write(saved, codec.serialize(vault));
        }
        List<String> before = flatten(export(original));
        List<String> after = flatten(export(saved));
        List<String> removed = new ArrayList<>(before);
        after.forEach(removed::remove);
        List<String> added = new ArrayList<>(after);
        before.forEach(added::remove);
        // 只允許被改的那個 Notes 值不同（測試用的 setPropertyValue 不更新修改時間）
        assertEquals(1, removed.size(), "原檔多出：" + removed);
        assertEquals(1, added.size(), "存檔後多出：" + added);
        assertTrue(added.get(0).endsWith("=S-Vault 修改過"), added.get(0));
    }

    @Test
    @DisplayName("AC-02-2、AC-02-3：VaultEditor 編輯後 KeePassXC 看到歷史版本、受保護的密碼、資源回收桶與刪除紀錄")
    void keePassXcSeesEditorChanges() throws Exception {
        VaultCodec codec = new VaultCodec();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00.500Z"), ZoneOffset.UTC);
        Path file = tmp.resolve("edited.kdbx");
        UUID edited;
        UUID recycled;
        UUID deleted;
        UUID bin;
        try (Vault vault = codec.create("編輯互通", TestVaults.password())) {
            VaultEditor editor = new VaultEditor(vault, clock);
            UUID work = editor.addGroup(vault.id(), "工作");
            edited = editor.addEntry(work, new EntryDraft("GitHub", "rory", "第一版", "https://github.com", ""));
            recycled = editor.addEntry(vault.id(), new EntryDraft("要回收的", "", "x", "", ""));
            deleted = editor.addEntry(vault.id(), new EntryDraft("要永久刪除的", "", "y", "", ""));
            clock.advanceSeconds(60);
            editor.updateEntry(edited, new EntryDraft("GitHub", "rory", "第二版", "https://github.com", ""));
            clock.advanceSeconds(60);
            editor.updateEntry(edited, new EntryDraft("GitHub", "rory", "第三版", "https://github.com", "備註"));
            editor.recycleEntry(recycled);
            editor.recycleEntry(deleted);
            editor.deleteEntryPermanently(deleted);
            bin = vault.groups().stream().filter(GroupView::recycleBin).findFirst().orElseThrow().uuid();
            Files.write(file, codec.serialize(vault));
        }

        Document xml = export(file);
        Element entry = entryByUuid(xml, edited);
        assertEquals("第三版", stringValue(entry, "Password"));
        assertEquals("True", passwordElement(entry).getAttribute("ProtectInMemory"), "密碼要受保護（P15）");
        assertEquals(Helpers.fromDateV4(Date.from(Instant.parse("2026-10-18T08:02:00Z"))),
                timesValue(entry, "LastModificationTime"), "修改時間是注入時鐘的時間、截到秒");
        Element history = directChild(entry, "History");
        List<Element> versions = directChildren(history, "Entry");
        assertEquals(2, versions.size(), "兩次修改 → 兩個歷史版本");
        assertEquals("第一版", stringValue(versions.get(0), "Password"));
        assertEquals("第二版", stringValue(versions.get(1), "Password"));
        assertEquals("True", passwordElement(versions.get(0)).getAttribute("ProtectInMemory"));

        Element meta = directChild(xml.getDocumentElement(), "Meta");
        assertEquals(Helpers.base64FromUuid(bin), directChild(meta, "RecycleBinUUID").getTextContent());
        Element recycledEntry = entryByUuid(xml, recycled);
        assertEquals(Helpers.base64FromUuid(bin),
                directChild((Element) recycledEntry.getParentNode(), "UUID").getTextContent());

        Element root = directChild(xml.getDocumentElement(), "Root");
        List<Element> deletions = directChildren(directChild(root, "DeletedObjects"), "DeletedObject");
        assertEquals(1, deletions.size());
        assertEquals(Helpers.base64FromUuid(deleted), directChild(deletions.get(0), "UUID").getTextContent());
        assertEquals(2, countNonHistory(xml), "永久刪除的條目不在檔案裡（只剩修改的與回收的）");
    }

    @Test
    @DisplayName("AC-02-2：KeePassXC 檔中已有 3 個歷史版本的條目，VaultEditor 修改後 KeePassXC 看到 4 個，其他內容不變")
    void keePassXcSeesHistoryOnFixture() throws Exception {
        Path original = tmp.resolve("original.kdbx");
        Path saved = tmp.resolve("saved.kdbx");
        byte[] fixture = TestVaults.fixture(TestVaults.KXC_GROUPS_RECYCLE_HISTORY);
        Files.write(original, fixture);
        VaultCodec codec = new VaultCodec();
        UUID target;
        try (Vault vault = codec.open(fixture, TestVaults.password())) {
            EntryView e = vault.entries().stream().filter(x -> x.historyCount() == 3).findFirst().orElseThrow();
            target = e.uuid();
            MutableClock clock = new MutableClock(Instant.parse("2026-10-18T09:00:00Z"), ZoneOffset.UTC);
            new VaultEditor(vault, clock).updateEntry(target, new EntryDraft(e.title(), e.username(),
                    "S-Vault 改的", e.url(), e.notes()));
            Files.write(saved, codec.serialize(vault));
        }
        Document before = export(original);
        Document after = export(saved);
        Element entry = entryByUuid(after, target);
        assertEquals(4, directChildren(directChild(entry, "History"), "Entry").size());
        assertEquals("S-Vault 改的", stringValue(entry, "Password"));
        // 被改的條目以外，整份 XML 完全相同
        entryByUuid(before, target).getParentNode().removeChild(entryByUuid(before, target));
        entry.getParentNode().removeChild(entry);
        List<String> b = flatten(before);
        List<String> a = flatten(after);
        assertEquals(b, a);
    }

    static Element entryByUuid(Document xml, UUID uuid) {
        String id = Helpers.base64FromUuid(uuid);
        NodeList entries = xml.getElementsByTagName("Entry");
        for (int i = 0; i < entries.getLength(); i++) {
            Element entry = (Element) entries.item(i);
            if (!entry.getParentNode().getNodeName().equals("History")
                    && directChild(entry, "UUID").getTextContent().equals(id)) {
                return entry;
            }
        }
        throw new AssertionError("KeePassXC 匯出中找不到條目 " + uuid);
    }

    private static int countNonHistory(Document xml) {
        int n = 0;
        NodeList entries = xml.getElementsByTagName("Entry");
        for (int i = 0; i < entries.getLength(); i++) {
            if (!entries.item(i).getParentNode().getNodeName().equals("History")) {
                n++;
            }
        }
        return n;
    }

    private static Element passwordElement(Element entry) {
        for (Element s : directChildren(entry, "String")) {
            if (directChild(s, "Key").getTextContent().equals("Password")) {
                return directChild(s, "Value");
            }
        }
        throw new AssertionError("沒有 Password 欄位");
    }

    private static String timesValue(Element entry, String name) {
        return directChild(directChild(entry, "Times"), name).getTextContent();
    }

    static Element directChild(Element parent, String name) {
        List<Element> found = directChildren(parent, name);
        if (found.isEmpty()) {
            throw new AssertionError("沒有 <" + name + ">");
        }
        return found.get(0);
    }

    static List<Element> directChildren(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && ((Element) child).getTagName().equals(name)) {
                out.add((Element) child);
            }
        }
        return out;
    }

    /** 以 keepassxc-cli 匯出明文 XML（只在記憶體中，不寫檔）。 */
    static Document export(Path file) throws Exception {
        Result result = run(Arrays.asList(cli, "export", "-q", "-f", "xml", file.toString()),
                (TestVaults.PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, result.exitCode, "keepassxc-cli 無法開啟 S-Vault 存的檔案");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(result.stdout));
    }

    /** 以 keepassxc-cli 讀出附件內容（XML 匯出不含附件內容；只在記憶體中，不寫檔）。 */
    static byte[] attachment(Path file, String entryPath, String name) throws IOException {
        Result result = run(Arrays.asList(cli, "attachment-export", "-q", "--stdout", file.toString(), entryPath, name),
                (TestVaults.PASSWORD + "\n").getBytes(StandardCharsets.UTF_8));
        assertEquals(0, result.exitCode, "keepassxc-cli 無法匯出附件");
        return result.stdout;
    }

    /**
     * 把 XML 攤平成「路徑[屬性]=文字」的清單。略過每次存檔都會變的 Generator 與 HeaderHash，以及
     * {@code Meta/CustomData} 的 {@code _LAST_MODIFIED}：keepassxc-cli 匯出時會把它改成當下時間，
     * 兩次匯出跨秒就不同（T-04 實測），不是檔案的內容。
     */
    private static List<String> flatten(Document xml) {
        List<String> out = new ArrayList<>();
        flatten(xml.getDocumentElement(), "", out);
        return out;
    }

    private static void flatten(Element element, String path, List<String> out) {
        String name = element.getTagName();
        if (name.equals("Generator") || name.equals("HeaderHash")) {
            return;
        }
        String here = path + "/" + name;
        if (here.equals("/KeePassFile/Meta/CustomData/Item")
                && element.getElementsByTagName("Key").item(0).getTextContent().equals("_LAST_MODIFIED")) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.TEXT_NODE) {
                text.append(child.getNodeValue());
            }
        }
        StringBuilder attrs = new StringBuilder();
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            Node a = element.getAttributes().item(i);
            attrs.append('[').append(a.getNodeName()).append('=').append(a.getNodeValue()).append(']');
        }
        out.add(here + attrs + "=" + text.toString().trim());
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                flatten((Element) child, here, out);
            }
        }
    }

    private static String stringValue(Element entry, String key) {
        for (Node child = entry.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && ((Element) child).getTagName().equals("String")) {
                Element s = (Element) child;
                if (s.getElementsByTagName("Key").item(0).getTextContent().equals(key)) {
                    return s.getElementsByTagName("Value").item(0).getTextContent();
                }
            }
        }
        return "";
    }

    private static final class Result {
        final int exitCode;
        final byte[] stdout;

        Result(int exitCode, byte[] stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout;
        }
    }

    private static Result run(List<String> command, byte[] stdin) throws IOException {
        Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try (OutputStream in = process.getOutputStream()) {
            if (stdin != null) {
                in.write(stdin);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream stdout = process.getInputStream()) {
            stdout.transferTo(out);
        }
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, out.toByteArray());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
        return new Result(process.exitValue(), out.toByteArray());
    }
}
