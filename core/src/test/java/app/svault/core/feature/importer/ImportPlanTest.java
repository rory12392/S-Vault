package app.svault.core.feature.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.core.module.EntryBatch;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryQuery;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultEditor;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ImportPlan}：可能重複的判定（AC-14-4）、選擇略過的列、匯入到「Chrome 匯入」群組（AC-14-1、AC-14-2）。
 */
class ImportPlanTest {

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-22T08:00:00Z"), ZoneOffset.UTC);
    private Vault vault;
    private VaultEditor editor;
    private ChromeCsv sample;

    @BeforeEach
    void open() throws Exception {
        byte[] password = TestVaults.password();
        try {
            vault = codec.create("匯入測試", password);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
        editor = new VaultEditor(vault, clock);
        try (InputStream in = getClass().getResourceAsStream("chrome-fake.csv")) {
            sample = ChromeCsvParser.read(in);
        }
    }

    @AfterEach
    void close() {
        vault.close();
    }

    @Test
    @DisplayName("AC-14-4：主機名稱（忽略 www. 與大小寫）與帳號都相同的列標示為可能重複，列出既有條目")
    void marksPossibleDuplicates() {
        UUID alice = editor.addEntry(vault.id(),
            new EntryDraft("舊的 Example", "Alice@Example.com", "old", "https://example.com/", ""));
        UUID bob1 = editor.addEntry(vault.id(), new EntryDraft("商店", "bob", "old", "SHOP.example.org", ""));
        UUID bob2 = editor.addEntry(vault.id(), new EntryDraft("商店 2", " bob ", "old", "http://shop.example.org:80", ""));
        editor.addEntry(vault.id(), new EntryDraft("同網站別的帳號", "someone", "old", "https://forum.example.net", ""));
        UUID recycled = editor.addEntry(vault.id(), new EntryDraft("回收的", "carol", "old", "https://forum.example.net", ""));
        assertTrue(editor.recycleEntry(recycled));

        ImportPlan plan = ImportPlan.of(sample, EntryQuery.of(vault).all());

        List<ImportCandidate> c = plan.candidates();
        assertEquals(4, c.size());
        assertEquals(List.of(alice), c.get(0).duplicateOf());
        assertEquals(Set.of(bob1, bob2), Set.copyOf(c.get(1).duplicateOf()));
        assertFalse(c.get(2).possibleDuplicate(), "資源回收桶裡的條目不算");
        assertFalse(c.get(3).possibleDuplicate());
        assertEquals(2, plan.duplicateCount());
    }

    @Test
    @DisplayName("AC-14-4：網址沒有主機名稱的列與條目不算重複，即使帳號相同")
    void noHostNoDuplicate() throws Exception {
        editor.addEntry(vault.id(), new EntryDraft("沒網址", "alice", "old", "", ""));
        ChromeCsv csv = ChromeCsvParser.parse(
            "name,url,username,password\nA,,alice,p\n".getBytes(StandardCharsets.UTF_8));

        ImportPlan plan = ImportPlan.of(csv, EntryQuery.of(vault).all());

        assertFalse(plan.candidates().get(0).possibleDuplicate());
    }

    @Test
    @DisplayName("AC-14-4：使用者選擇略過的列不匯入，照樣匯入的列照常產生草稿")
    void draftsHonourSkips() {
        editor.addEntry(vault.id(), new EntryDraft("舊的", "alice@example.com", "old", "https://example.com", ""));
        ImportPlan plan = ImportPlan.of(sample, EntryQuery.of(vault).all());

        Set<Integer> duplicates = plan.candidates().stream().filter(ImportCandidate::possibleDuplicate)
            .map(c -> c.row().line()).collect(Collectors.toSet());
        assertEquals(Set.of(2), duplicates);

        assertEquals(List.of("shop.example.org", "forum.example.net", "com.example.app"),
            plan.drafts(duplicates).stream().map(EntryDraft::title).toList());
        assertEquals(4, plan.drafts(Set.of()).size());
    }

    @Test
    @DisplayName("AC-14-1、AC-14-2：整批是「Chrome 匯入」群組＋全部的列，寫進保管庫後內容與原檔一致，存檔後讀得回來")
    void batchIntoNewGroup() throws Exception {
        ImportPlan plan = ImportPlan.of(sample, EntryQuery.of(vault).all());

        EntryBatch batch = plan.batch(Set.of()).orElseThrow();
        assertEquals("Chrome 匯入", batch.groupName());
        assertEquals(4, batch.size());
        // 外殼的 ModuleVaultWriter 做的事（T-33）：根群組底下新建群組、依序加入；這裡只驗證內容一路保留
        UUID group = editor.addGroup(vault.id(), batch.groupName());
        batch.entries().forEach(d -> editor.addEntry(group, d));

        byte[] password = TestVaults.password();
        try (Vault reopened = codec.open(codec.serialize(vault), password)) {
            GroupView g = reopened.groups().stream().filter(x -> x.uuid().equals(group)).findFirst().orElseThrow();
            assertEquals(ImportPlan.GROUP_NAME, g.name());
            assertEquals("Chrome 匯入", g.name());
            assertEquals(vault.id(), g.parentId());
            List<EntryView> imported = reopened.entries().stream().filter(e -> e.groupId().equals(group))
                .sorted((a, b) -> a.title().compareTo(b.title())).toList();
            assertEquals(4, imported.size());
            EntryView forum = imported.stream().filter(e -> e.username().equals("carol")).findFirst().orElseThrow();
            assertEquals("forum.example.net", forum.title());
            assertEquals("https://forum.example.net/", forum.url());
            assertEquals("Fake-Pass-003", forum.password());
            assertEquals("第一行，有逗號, comma\n第二行", forum.notes());
            EntryView shop = imported.stream().filter(e -> e.username().equals("bob")).findFirst().orElseThrow();
            assertEquals("pa,ss\"word", shop.password());
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    @Test
    @DisplayName("全部略過時沒有整批（畫面不呼叫寫入）；略過的列不在整批裡")
    void emptyWhenAllSkipped() {
        ImportPlan plan = ImportPlan.of(sample, EntryQuery.of(vault).all());

        assertTrue(plan.batch(Set.of(2, 3, 4, 6)).isEmpty());
        assertEquals(List.of("shop.example.org", "forum.example.net", "com.example.app"),
            plan.batch(Set.of(2)).orElseThrow().entries().stream().map(EntryDraft::title).toList());
    }

    @Test
    @DisplayName("AC-14-1：預覽是前 10 筆，筆數是全部；跳過的列原樣帶著")
    void previewIsFirstTen() throws Exception {
        StringBuilder sb = new StringBuilder("name,url,username,password,note\nbroken\n");
        for (int i = 1; i <= 25; i++) {
            sb.append("n").append(i).append(",https://s").append(i).append(".example.com,u,p,\n");
        }
        ImportPlan plan = ImportPlan.of(ChromeCsvParser.parse(sb.toString().getBytes(StandardCharsets.UTF_8)),
            List.of());

        assertEquals(25, plan.size());
        assertEquals(ImportPlan.PREVIEW_SIZE, plan.preview().size());
        assertEquals("n10", plan.preview().get(9).row().name());
        assertEquals(List.of(new SkippedLine(2, SkippedLine.Reason.FIELD_COUNT)), plan.skipped());
        assertEquals(4, ImportPlan.of(sample, List.of()).preview().size(), "不到 10 筆時全部預覽");
    }

    @Test
    @DisplayName("候選列的 toString 不含內容")
    void candidateToStringHidesContent() {
        ImportPlan plan = ImportPlan.of(sample, List.of());
        String s = plan.candidates().get(0).toString() + plan;
        assertFalse(s.contains("Fake-Pass"));
        assertFalse(s.contains("alice"));
    }
}
