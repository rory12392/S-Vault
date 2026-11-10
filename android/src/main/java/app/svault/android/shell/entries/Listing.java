package app.svault.android.shell.entries;

import app.svault.core.shell.ScreenData;
import app.svault.core.vault.EntryView;
import java.util.List;
import java.util.UUID;

/**
 * 條目清單目前要列出什麼（不依賴 Android，可以單元測試）。
 *
 * @param entries 依名稱排序的條目
 * @param empty   清單是空的時候顯示哪一種說明
 * @param search  是否為搜尋結果（每列多顯示群組路徑）
 */
record Listing(List<EntryView> entries, Empty empty, boolean search) {

    /** 空清單的種類（AC-02-6、AC-03-3）。 */
    enum Empty {
        /** 不是空的。 */
        NONE,
        /** 整個保管庫都沒有條目：「建立你的第一筆條目」＋「新增條目」。 */
        VAULT,
        /** 這個群組沒有條目：「這個群組還沒有條目」＋「新增條目」。 */
        GROUP,
        /** 資源回收桶是空的（不能在這裡新增）。 */
        RECYCLE_BIN,
        /** 搜尋沒有結果：「找不到符合的條目」。 */
        NO_RESULTS
    }

    /**
     * 清單的範圍：首頁點了哪一列。
     *
     * @param kind    種類
     * @param groupId 群組；「全部條目」與資源回收桶為 {@code null}
     */
    record Scope(Kind kind, UUID groupId) {

        /** 範圍的種類。 */
        enum Kind {
            /** 全部條目（資源回收桶以外）。 */
            ALL,
            /** 一個群組（只列直接放在裡面的，同 KeePassXC）。 */
            GROUP,
            /** 資源回收桶（裡面所有條目，含被刪除群組裡的）。 */
            BIN
        }

        /** @return 全部條目 */
        static Scope all() {
            return new Scope(Kind.ALL, null);
        }

        /** @param groupId 群組 @return 一個群組（或回收桶裡的群組） */
        static Scope group(UUID groupId) {
            return new Scope(Kind.GROUP, groupId);
        }

        /** @return 資源回收桶 */
        static Scope bin() {
            return new Scope(Kind.BIN, null);
        }
    }

    /**
     * @param data    目前的內容
     * @param scope   首頁點了哪一列
     * @param keyword 搜尋關鍵字；空白時不是搜尋
     * @return 要列出的條目
     */
    static Listing of(ScreenData data, Scope scope, String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (!k.isEmpty()) {
            // 搜尋一律找全部群組（AC-03-1：兩頁的 SearchBar 都搜尋全部條目）；不含資源回收桶
            List<EntryView> found = data.query().search(k);
            return new Listing(found, found.isEmpty() ? Empty.NO_RESULTS : Empty.NONE, true);
        }
        List<EntryView> list;
        Empty empty;
        switch (scope.kind()) {
            case BIN:
                list = data.query().recycled();
                empty = Empty.RECYCLE_BIN;
                break;
            case GROUP:
                list = data.group(scope.groupId()).isPresent() ? data.query().inGroup(scope.groupId()) : List.of();
                empty = data.vaultEmpty() ? Empty.VAULT : data.inRecycleBin(scope.groupId()) ? Empty.RECYCLE_BIN
                        : Empty.GROUP;
                break;
            default:
                list = data.query().all();
                empty = Empty.VAULT;
                break;
        }
        return new Listing(list, list.isEmpty() ? empty : Empty.NONE, false);
    }
}
