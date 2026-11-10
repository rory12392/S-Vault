package app.svault.core.sync;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 一筆衝突的條目（AC-08-1）：兩邊在上次同步後都改了同一個欄位、而且改成不同的值。整筆條目進入衝突清單，
 * 其他沒衝突的欄位照常合併。衝突對照畫面（T-15／T-16）逐欄列出 {@link #fields()} 與兩邊的修改時間。
 *
 * <p>欄位值是使用者的內容（可能是密碼），{@code toString()} 只輸出 UUID。
 *
 * @param entryId        條目
 * @param fields         衝突的欄位（依欄位鍵排序）
 * @param localModified  這台裝置的修改時間
 * @param remoteModified 另一台裝置的修改時間
 */
public record Conflict(UUID entryId, List<Field> fields, Instant localModified, Instant remoteModified) {

    /** 所在群組的欄位鍵（值是群組 UUID 的字串；其他欄位鍵見 {@code EntrySnapshot}）。 */
    public static final String GROUP = "group";

    /**
     * 複製成不可修改的版本。
     */
    public Conflict {
        fields = List.copyOf(fields);
    }

    /**
     * 整筆選同一邊（AC-08-2 的「保留這台裝置」「保留另一台裝置」）。
     *
     * @param side 選哪一邊
     * @return 傳給 {@link MergeResult#resolve} 的這筆條目的選擇
     */
    public Map<String, Side> choose(Side side) {
        Map<String, Side> out = new LinkedHashMap<>();
        for (Field f : fields) {
            out.put(f.key(), side);
        }
        return out;
    }

    @Override
    public String toString() {
        return "Conflict[" + entryId + "]";
    }

    /**
     * 一個衝突的欄位。
     *
     * @param key         欄位鍵（{@code EntrySnapshot} 的鍵或 {@link #GROUP}）
     * @param localValue  這台裝置的值；這台裝置刪掉了這個欄位時為 {@code null}
     * @param remoteValue 另一台裝置的值；另一台刪掉了這個欄位時為 {@code null}
     */
    public record Field(String key, String localValue, String remoteValue) {

        /** 自訂欄位的名稱也是使用者的內容，什麼都不輸出。 */
        @Override
        public String toString() {
            return "Field[***]";
        }
    }
}
