package app.svault.core.feature.importer;

import app.svault.core.module.EntryBatch;
import app.svault.core.vault.EntryDraft;
import app.svault.core.vault.EntryView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 匯入精靈的資料：預覽、可能重複的判定與匯入（US-14）。畫面（T-61、T-62）只負責顯示與收集使用者的選擇。
 *
 * <p>「可能重複」（AC-14-4）：網址的主機名稱（忽略 {@code www.} 與大小寫、不看協定、路徑與連接埠）相同，
 * 而且帳號（去除前後空白、不分大小寫）相同。網址取不出主機名稱的列與條目不參與比對。
 * 只比對傳進來的既有條目：呼叫端傳 {@code EntryQuery#all()}，資源回收桶裡的條目不算。
 *
 * <p>建立後不可變，可以在任何執行緒使用。
 */
public final class ImportPlan {

    /** 匯入的條目放進的新群組名稱（AC-14-1）。 */
    public static final String GROUP_NAME = "Chrome 匯入";

    /** 預覽的筆數（AC-14-1：前 10 筆）。 */
    public static final int PREVIEW_SIZE = 10;

    /** 比對鍵中主機名稱與帳號之間的分隔字元：兩者都不會含有它，不會拼出假的相同。 */
    private static final char SEPARATOR = '\u0000';

    private final List<ImportCandidate> candidates;
    private final List<SkippedLine> skipped;

    private ImportPlan(List<ImportCandidate> candidates, List<SkippedLine> skipped) {
        this.candidates = Collections.unmodifiableList(candidates);
        this.skipped = skipped;
    }

    /**
     * @param csv      解析結果
     * @param existing 保管庫中的既有條目（{@code EntryQuery#all()}）
     * @return 匯入計畫
     */
    public static ImportPlan of(ChromeCsv csv, List<EntryView> existing) {
        Map<String, List<UUID>> byKey = new HashMap<>();
        for (EntryView e : existing) {
            String key = key(e.url(), e.username());
            if (key != null) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(e.uuid());
            }
        }
        List<ImportCandidate> out = new ArrayList<>(csv.rows().size());
        for (ChromeCsvRow row : csv.rows()) {
            String key = key(row.url(), row.username());
            List<UUID> dup = key == null ? null : byKey.get(key);
            out.add(new ImportCandidate(row, dup == null ? List.of() : dup));
        }
        return new ImportPlan(out, csv.skipped());
    }

    /**
     * @return 所有可以匯入的列，依檔案順序
     */
    public List<ImportCandidate> candidates() {
        return candidates;
    }

    /**
     * @return 前 {@link #PREVIEW_SIZE} 筆（AC-14-1 的預覽）
     */
    public List<ImportCandidate> preview() {
        return candidates.subList(0, Math.min(PREVIEW_SIZE, candidates.size()));
    }

    /**
     * @return 可以匯入的筆數（預覽顯示的筆數）
     */
    public int size() {
        return candidates.size();
    }

    /**
     * @return 標示為可能重複的筆數
     */
    public int duplicateCount() {
        int n = 0;
        for (ImportCandidate c : candidates) {
            if (c.possibleDuplicate()) {
                n++;
            }
        }
        return n;
    }

    /**
     * @return 格式錯誤而跳過的列（AC-14-2，畫面列出行號）
     */
    public List<SkippedLine> skipped() {
        return skipped;
    }

    /**
     * @param skippedLines 使用者選擇略過的列（{@link ChromeCsvRow#line()}）
     * @return 其餘各列的條目草稿，依檔案順序；名稱一定不是空白（{@link ChromeCsvRow#toDraft()}）
     */
    public List<EntryDraft> drafts(Set<Integer> skippedLines) {
        List<EntryDraft> out = new ArrayList<>();
        for (ImportCandidate c : candidates) {
            if (!skippedLines.contains(c.row().line())) {
                out.add(c.row().toDraft());
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * 要交給外殼的一整批（AC-14-1）：群組 {@link #GROUP_NAME}＋略過以外的列。外殼的 {@code ModuleHost.addEntries}
     * （T-33）在根群組底下新建這個群組（已有同名的照樣新建）、全部加入後存檔一次，失敗時一筆都不留。
     * 模組拿不到 {@code VaultEditor}，所以這裡只產生資料、不寫入（T-61 起取代原本的 {@code applyTo}）。
     *
     * @param skippedLines 使用者選擇略過的列（{@link ChromeCsvRow#line()}）
     * @return 整批；全部略過、沒有要匯入的列時為空（不要呼叫 {@code addEntries}）
     * @throws IllegalArgumentException 超過 {@link EntryBatch#MAX_ENTRIES} 筆
     */
    public Optional<EntryBatch> batch(Set<Integer> skippedLines) {
        List<EntryDraft> drafts = drafts(skippedLines);
        if (drafts.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new EntryBatch(GROUP_NAME, drafts));
    }

    /**
     * 只輸出筆數，不含內容（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "ImportPlan[candidates=" + candidates.size() + ", skipped=" + skipped.size() + "]";
    }

    /** 比對鍵；網址取不出主機名稱時為 {@code null}（不參與比對）。 */
    private static String key(String url, String username) {
        String host = HostNames.comparable(url);
        if (host.isEmpty()) {
            return null;
        }
        return host + SEPARATOR + username.trim().toLowerCase(Locale.ROOT);
    }
}
