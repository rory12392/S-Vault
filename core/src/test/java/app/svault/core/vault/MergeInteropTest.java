package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.storage.MutableClock;
import app.svault.core.sync.MergeResult;
import app.svault.core.sync.ThreeWayMerger;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 用真的 KeePassXC 開合併後的檔案（Architecture §4 第 7 點、AC-01-2）。合併會把條目從本機那份資料庫複製到遠端那份，
 * 附件要搬到另一個保管庫的二進位資料區、重建參照（{@code KdbxCopy}）；這裡確認 KeePassXC 讀到的附件內容與原檔相同。
 * 只在找得到 {@code keepassxc-cli} 時執行（同 {@link KeePassXcInteropTest}）。
 */
class MergeInteropTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void findCli() {
        KeePassXcInteropTest.findCli();
    }

    @Test
    @DisplayName("AC-07-4、AC-01-2：遠端刪除、本機修改的條目經合併複製回遠端，KeePassXC 看到相同的附件與自訂欄位")
    void keePassXcSeesEntryCopiedByMerge() throws Exception {
        byte[] fixture = TestVaults.fixture(TestVaults.KXC_ATTACHMENTS_FIELDS);
        Path original = tmp.resolve("original.kdbx");
        Path merged = tmp.resolve("merged.kdbx");
        Files.write(original, fixture);
        VaultCodec codec = new VaultCodec();
        MutableClock clock = new MutableClock(Instant.parse("2026-10-20T03:00:00Z"), ZoneOffset.UTC);
        UUID target;
        try (Vault base = codec.open(fixture, TestVaults.password());
             Vault local = codec.openWithKeyOf(fixture, base);
             Vault remote = codec.openWithKeyOf(fixture, base)) {
            EntryView e = local.entries().stream().filter(x -> !x.attachmentNames().isEmpty()).findFirst()
                    .orElseThrow();
            target = e.uuid();
            new VaultEditor(local, clock).updateEntry(target, new EntryDraft(e.title(), e.username(), e.password(),
                    e.url(), "本機改的備註"));
            VaultEditor other = new VaultEditor(remote, clock);
            other.recycleEntry(target);
            other.deleteEntryPermanently(target);

            MergeResult result = ThreeWayMerger.merge(base.snapshot(), local.snapshot(), remote.snapshot());
            MergeWriter.apply(remote, local, result.plan());
            Files.write(merged, codec.serialize(remote));
        }

        Document before = KeePassXcInteropTest.export(original);
        Document after = KeePassXcInteropTest.export(merged);
        Element was = KeePassXcInteropTest.entryByUuid(before, target);
        Element now = KeePassXcInteropTest.entryByUuid(after, target);
        Map<String, String> attachments = attachments(original, was);
        assertFalse(attachments.isEmpty());
        assertEquals(attachments, attachments(merged, now), "附件名稱與內容");
        assertEquals(strings(was, "Notes"), strings(now, "Notes"), "標準欄位以外的字串欄位（自訂欄位）");
        assertTrue(KeePassXcInteropTest.directChildren(
                KeePassXcInteropTest.directChild(now, "History"), "Entry").size() >= 1, "修改前的版本在歷史紀錄裡");
    }

    /**
     * keepassxc-cli 2.7 在 Windows 的 {@code --stdout} 會把不是 UTF-8 的位元組換成 U+FFFD（2026-10-20 實測），
     * 所以這裡確認的是「KeePassXC 讀得到同名、同長度輪廓的附件」；位元組完全相同由合併案例的 SHA-256 比對保證
     * （{@code MergeCases} 的 AC-01-2 案例）。
     *
     * @return 附件名稱 → 內容（Base64），以 keepassxc-cli 讀出
     */
    private static Map<String, String> attachments(Path file, Element entry) throws IOException {
        Map<String, String> out = new HashMap<>();
        for (Element b : KeePassXcInteropTest.directChildren(entry, "Binary")) {
            String name = KeePassXcInteropTest.directChild(b, "Key").getTextContent();
            byte[] content = KeePassXcInteropTest.attachment(file, path(entry), name);
            out.put(name, Base64.getEncoder().encodeToString(content));
        }
        return out;
    }

    /** @return keepassxc-cli 的條目路徑（根群組以下的群組名稱／標題） */
    private static String path(Element entry) {
        StringBuilder path = new StringBuilder(strings(entry, "").get("Title"));
        for (Node n = entry.getParentNode(); n instanceof Element && n.getParentNode() instanceof Element
                && ((Element) n.getParentNode()).getTagName().equals("Group"); n = n.getParentNode()) {
            path.insert(0, KeePassXcInteropTest.directChild((Element) n, "Name").getTextContent() + "/");
        }
        return path.toString();
    }

    /** @return 字串欄位名稱 → 值，略過 {@code except} */
    private static Map<String, String> strings(Element entry, String except) {
        Map<String, String> out = new HashMap<>();
        for (Element s : KeePassXcInteropTest.directChildren(entry, "String")) {
            String key = KeePassXcInteropTest.directChild(s, "Key").getTextContent();
            if (!key.equals(except)) {
                out.put(key, KeePassXcInteropTest.directChild(s, "Value").getTextContent());
            }
        }
        return out;
    }
}
