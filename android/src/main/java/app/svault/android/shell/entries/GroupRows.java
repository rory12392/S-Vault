package app.svault.android.shell.entries;

import app.svault.core.shell.ScreenData;
import app.svault.core.vault.GroupView;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 首頁的群組清單：「全部條目」→ 根群組底下的群組（依檔案順序的前序，以縮排表示
 * 層級）→「資源回收桶」固定在最後，裡面被刪除的群組也列出（可以還原）。檔案還沒有回收桶時也顯示一個空的。
 *
 * <p>每列的數字是點進去會列出的筆數：「全部條目」不含回收桶、群組只算直接放在裡面的（子群組各自一列）、
 * 回收桶算裡面全部。
 */
final class GroupRows {

    /** 列的種類。 */
    enum Kind {
        /** 全部條目。 */
        ALL,
        /** 一般群組。 */
        GROUP,
        /** 資源回收桶。 */
        BIN,
        /** 資源回收桶裡被刪除的群組。 */
        RECYCLED
    }

    /**
     * @param kind    種類
     * @param groupId 群組；「全部條目」與還不存在的回收桶為 {@code null}
     * @param name    群組名稱；「全部條目」與回收桶由畫面放固定文字，這裡是空字串
     * @param depth   縮排層級（根群組的子群組、回收桶裡的群組是 0／1）
     * @param count   條目數
     */
    record Row(Kind kind, UUID groupId, String name, int depth, int count) {

        /** 群組名稱是使用者的內容，不輸出。 */
        @Override
        public String toString() {
            return "Row[" + kind + "]";
        }
    }

    private GroupRows() {
    }

    /**
     * @param data 目前的內容
     * @return 依顯示順序的列
     */
    static List<Row> of(ScreenData data) {
        List<Row> out = new ArrayList<>();
        out.add(new Row(Kind.ALL, null, "", 0, data.query().all().size()));
        UUID binId = data.recycleBin().map(GroupView::uuid).orElse(null);
        for (GroupView child : data.children(data.root().uuid())) {
            if (!child.uuid().equals(binId)) {
                add(data, child, Kind.GROUP, 0, out);
            }
        }
        out.add(new Row(Kind.BIN, binId, "", 0, data.query().recycled().size()));
        if (binId != null) {
            for (GroupView child : data.children(binId)) {
                add(data, child, Kind.RECYCLED, 1, out);
            }
        }
        return List.copyOf(out);
    }

    private static void add(ScreenData data, GroupView group, Kind kind, int depth, List<Row> out) {
        out.add(new Row(kind, group.uuid(), group.name(), depth, data.directCount(group.uuid())));
        for (GroupView child : data.children(group.uuid())) {
            add(data, child, kind, depth + 1, out);
        }
    }
}
