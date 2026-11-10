package app.svault.core.sync;

import app.svault.core.vault.EntrySnapshot;
import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 合併結果與檢查用的小工具；檢查失敗時把訊息加進失敗清單。 */
public final class MergeOutcome {
    private final MergeResult first;
    private final MergeResult result;
    private final Vault merged;
    private final VaultSnapshot localBefore;
    private final Map<String, UUID> ids;
    private final List<String> failures;

    /** KDBX 的標準五欄（其他字串欄位是自訂欄位）。 */
    private static final List<String> STANDARD =
            Arrays.asList("Title", "UserName", "Password", "URL", "Notes");

    MergeOutcome(MergeResult first, MergeResult result, Vault merged, VaultSnapshot localBefore,
            Map<String, UUID> ids, List<String> failures) {
        this.first = first;
        this.result = result;
        this.merged = merged;
        this.localBefore = localBefore;
        this.ids = ids;
        this.failures = failures;
    }

    /** @return 第一次合併的結果（衝突解決前） */
    public MergeResult firstResult() {
        return first;
    }

    /** @return 最後的結果（有 resolver 時是解決後的） */
    public MergeResult result() {
        return result;
    }

    /** @return 套用並重新開啟後的保管庫；還有衝突（沒有套用）時為 {@code null} */
    public Vault merged() {
        return merged;
    }

    /** @return 案例裡的名稱 → UUID */
    public UUID id(String name) {
        return ids.get(name);
    }

    /** 條件不成立時記一筆失敗。 */
    public void check(boolean ok, String message) {
        if (!ok) {
            failures.add(message);
        }
    }

    /** 合併沒有衝突。 */
    public void noConflicts() {
        check(!first.hasConflicts(), "不該有衝突，卻有 " + first.conflicts().size() + " 筆");
    }

    /** 條目在合併結果裡。 */
    public void present(String name) {
        check(entry(name) != null, name + " 應該還在");
    }

    /** 條目不在合併結果裡。 */
    public void absent(String name) {
        check(entry(name) == null, name + " 應該被刪除");
    }

    /** 標準欄位的值（{@code title}、{@code user}、{@code password}、{@code url}、{@code notes}）。 */
    public void field(String name, String field, String expected) {
        EntryView e = entry(name);
        if (e == null) {
            failures.add(name + " 不在結果裡，無法檢查 " + field);
            return;
        }
        String actual = field.equals("title") ? e.title() : field.equals("user") ? e.username()
                : field.equals("password") ? e.password() : field.equals("url") ? e.url() : e.notes();
        // 不印出實際值以外的內容；這些都是案例自己寫的假資料
        check(expected.equals(actual), name + " 的 " + field + " 應該是「" + expected + "」，實際是「" + actual + "」");
    }

    /** 條目所在的群組（{@code null}＝根群組）。 */
    public void inGroup(String name, String group) {
        EntryView e = entry(name);
        UUID want = group == null ? merged.id() : ids.get(group);
        check(e != null && e.groupId().equals(want), name + " 應該在 " + (group == null ? "根群組" : group));
    }

    /** 條目在資源回收桶裡（或回收桶底下的群組裡）。 */
    public void inRecycleBin(String name, boolean expected) {
        EntryView e = entry(name);
        boolean actual = e != null && merged.snapshot().inRecycleBin(e.groupId());
        check(actual == expected, name + (expected ? " 應該在資源回收桶裡" : " 不該在資源回收桶裡"));
    }

    /** 群組在結果裡（或不在）。 */
    public void groupPresent(String name, boolean expected) {
        boolean actual = group(ids.get(name)) != null;
        check(actual == expected, "群組 " + name + (expected ? " 應該還在" : " 應該被刪除"));
    }

    /** 群組在資源回收桶裡（或不在）。 */
    public void groupInRecycleBin(String name, boolean expected) {
        boolean actual = merged.snapshot().inRecycleBin(ids.get(name));
        check(actual == expected, "群組 " + name + (expected ? " 應該在資源回收桶裡" : " 不該在資源回收桶裡"));
    }

    /** 群組的名稱。 */
    public void groupName(String name, String expected) {
        GroupView g = group(ids.get(name));
        check(g != null && g.name().equals(expected), "群組 " + name + " 的名稱應該是「" + expected + "」");
    }

    /** 歷史版本數。 */
    public void history(String name, int expected) {
        EntryView e = entry(name);
        int actual = e == null ? -1 : e.historyCount();
        check(actual == expected, name + " 應該有 " + expected + " 個歷史版本，實際 " + actual);
    }

    /** 有這種通知（{@code name} 為 {@code null} 時不看對象）。 */
    public void notice(MergeNotice.Kind kind, String name) {
        UUID id = name == null ? null : ids.get(name);
        boolean found = false;
        for (MergeNotice n : first.notices()) {
            found |= n.kind() == kind && (id == null || id.equals(n.itemId()));
        }
        check(found, "應該有通知 " + kind + (name == null ? "" : "（" + name + "）"));
    }

    /** 沒有任何通知。 */
    public void noNotices() {
        check(first.notices().isEmpty(), "不該有通知，卻有 " + first.notices().size() + " 個");
    }

    /** 刪除紀錄裡有（或沒有）這個 UUID。 */
    public void deletedRecorded(String name, boolean expected) {
        boolean actual = merged.snapshot().deleted().containsKey(ids.get(name));
        check(actual == expected, name + (expected ? " 應該在 DeletedObjects 裡" : " 不該在 DeletedObjects 裡"));
    }

    /** 衝突的條目與欄位鍵（依鍵排序）。 */
    public void conflict(String name, String... keys) {
        Conflict found = null;
        for (Conflict c : first.conflicts()) {
            if (c.entryId().equals(ids.get(name))) {
                found = c;
            }
        }
        if (found == null) {
            failures.add(name + " 應該有衝突");
            return;
        }
        List<String> actual = new ArrayList<>();
        for (Conflict.Field f : found.fields()) {
            actual.add(f.key());
        }
        List<String> want = new ArrayList<>();
        for (String k : keys) {
            want.add(k);
        }
        check(actual.equals(want), name + " 的衝突欄位應該是 " + want + "，實際是 " + actual);
    }

    /** 和本機原本內容不同的條目數（AC-07-5 的「合併 n 筆」）。 */
    public void changed(int expected) {
        check(first.changedEntries() == expected,
                "應該有 " + expected + " 筆和本機不同，實際 " + first.changedEntries());
    }

    /** 歷史紀錄裡有一個版本的密碼是 {@code password}（沒被選的版本存入歷史，AC-08-2）。 */
    public void hasHistoryWithPassword(String name, String password) {
        boolean found = false;
        if (merged != null && entry(name) != null) {
            for (EntryView h : merged.history(ids.get(name))) {
                found |= h.password().equals(password);
            }
        }
        check(found, name + " 的歷史紀錄裡應該有密碼是「" + password + "」的版本");
    }

    /** 附件（名稱與內容）與自訂欄位都和合併前的本機相同（AC-01-2：S-Vault 不編輯的內容原樣保留）。 */
    public void attachmentsAndCustomFieldsSameAsLocal(String name) {
        EntrySnapshot before = localBefore.entries().get(ids.get(name));
        EntrySnapshot after = merged == null ? null : merged.snapshot().entries().get(ids.get(name));
        if (before == null || after == null) {
            failures.add(name + " 不在結果裡，無法比對附件與自訂欄位");
            return;
        }
        boolean any = false;
        for (Map.Entry<String, String> f : before.fields().entrySet()) {
            String key = f.getKey();
            boolean standard = key.startsWith(EntrySnapshot.STRING_PREFIX)
                    && STANDARD.contains(key.substring(EntrySnapshot.STRING_PREFIX.length()));
            if (key.startsWith(EntrySnapshot.ATTACHMENT_PREFIX) || key.startsWith(EntrySnapshot.STRING_PREFIX) && !standard) {
                any = true;
                check(f.getValue().equals(after.fields().get(key)), name + " 的附件或自訂欄位不同：" + key);
            }
        }
        check(any, name + " 在本機沒有附件或自訂欄位（案例設定錯誤）");
        check(before.fields().keySet().equals(after.fields().keySet()), name + " 的欄位清單和本機不同");
    }

    /** 結果裡名稱是 {@code name} 的群組數。 */
    public void groupsNamed(String groupName, int expected) {
        int n = 0;
        for (GroupView g : merged.groups()) {
            n += g.name().equals(groupName) ? 1 : 0;
        }
        check(n == expected, "名稱是「" + groupName + "」的群組應該有 " + expected + " 個，實際 " + n);
    }

    private EntryView entry(String name) {
        UUID id = ids.get(name);
        if (merged == null || id == null) {
            return null;
        }
        for (EntryView e : merged.entries()) {
            if (e.uuid().equals(id)) {
                return e;
            }
        }
        return null;
    }

    private GroupView group(UUID id) {
        if (merged == null || id == null) {
            return null;
        }
        for (GroupView g : merged.groups()) {
            if (g.uuid().equals(id)) {
                return g;
            }
        }
        return null;
    }
}
