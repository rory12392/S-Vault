package app.svault.core.storage;

import app.svault.core.vault.EntryView;
import app.svault.core.vault.GroupView;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import app.svault.core.vault.VaultException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 存檔的步驟 5：讀回驗證（Architecture §5；T-05）。用同一把金鑰解開剛序列化的位元組（0 次 KDF），內容要和記憶體中的
 * 完全相同。{@link SaveCoordinator} 用（T-27 從它拆出，CONVENTIONS §10 的檔案長度）。
 */
final class ReadBack {

    private ReadBack() {
    }

    static void verify(VaultCodec codec, byte[] bytes, Vault vault) throws SaveVerificationException {
        try (Vault check = codec.openWithKeyOf(bytes, vault)) {
            // 快照是 record，equals 比對每個欄位；只回報哪一類不同，不記內容
            if (!check.id().equals(vault.id())) {
                throw new SaveVerificationException("保管庫 ID 不一致");
            }
            if (!groupsAsStored(check).equals(groupsAsStored(vault))) {
                throw new SaveVerificationException("群組不一致");
            }
            if (check.entries().size() != vault.entries().size()) {
                throw new SaveVerificationException("條目數不一致");
            }
            if (!entriesAsStored(check).equals(entriesAsStored(vault))) {
                throw new SaveVerificationException("條目內容不一致");
            }
            if (!check.deletedObjects().equals(vault.deletedObjects())) {
                throw new SaveVerificationException("刪除紀錄不一致");
            }
            // 上面只比附件名稱與歷史筆數；名稱、筆數相同但內容不同的錯誤要靠完整的內容摘要（C-3）
            if (!check.contentDigest().equals(vault.contentDigest())) {
                throw new SaveVerificationException("附件或歷史內容不一致");
            }
        } catch (SaveVerificationException e) {
            throw e;
        } catch (VaultException e) {
            throw new SaveVerificationException("解不開：" + e.getClass().getSimpleName());
        }
    }

    // KDBX 4 的時間只存到秒；記憶體中剛建立或剛修改的物件可能帶毫秒。比對「檔案能表示的內容」，
    // 否則每次新建或編輯後的第一次存檔都會被誤判為讀回不一致
    private static List<GroupView> groupsAsStored(Vault v) {
        List<GroupView> out = new ArrayList<>();
        for (GroupView g : v.groups()) {
            out.add(new GroupView(g.uuid(), g.parentId(), g.name(), g.notes(), g.recycleBin(),
                    seconds(g.lastModified())));
        }
        return out;
    }

    private static List<EntryView> entriesAsStored(Vault v) {
        List<EntryView> out = new ArrayList<>();
        for (EntryView e : v.entries()) {
            out.add(new EntryView(e.uuid(), e.groupId(), e.title(), e.username(), e.password(), e.url(), e.notes(),
                    e.customFields(), e.attachmentNames(), e.tags(), seconds(e.created()), seconds(e.lastModified()),
                    e.historyCount()));
        }
        return out;
    }

    private static Instant seconds(Instant t) {
        return t == null ? null : t.truncatedTo(ChronoUnit.SECONDS);
    }
}
