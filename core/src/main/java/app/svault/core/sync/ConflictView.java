package app.svault.core.sync;

import app.svault.core.vault.EntrySnapshot;
import app.svault.core.vault.GroupSnapshot;
import app.svault.core.vault.VaultSnapshot;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 衝突對照畫面要顯示的內容（US-08），兩平台共用（T-15 建立，T-16 沿用）：每筆衝突條目的名稱、兩邊的
 * 修改時間，以及逐欄的「欄位種類＋兩邊的值」。欄位的名稱與「（空白）」「不到期」等用語由平台的字串資源決定
 * （CONVENTIONS §4），這裡只決定要用哪一種說法（{@link Shown.Form}）與要填進去的內容。
 *
 * <p>值是使用者的內容（可能是密碼），{@code toString()} 不輸出任何內容。
 *
 * @param items 衝突的條目（順序同 {@link MergeResult#conflicts()}，畫面的「第 n／N 筆」）
 */
public record ConflictView(List<Item> items) {

    private static final String TITLE = EntrySnapshot.stringKey("Title");
    /** 標準五欄（KDBX 的欄位名稱）依畫面順序。 */
    private static final List<String> STANDARD = List.of("Title", "UserName", "Password", "URL", "Notes");
    /** 附件只顯示內容雜湊的前幾碼，讓兩個版本看得出不同。 */
    private static final int HASH_DIGITS = 8;

    /**
     * 複製成不可修改的版本。
     */
    public ConflictView {
        items = List.copyOf(items);
    }

    /** 欄位種類（決定畫面上的欄位名稱與排列順序）。 */
    public enum Kind {
        /** 名稱。 */
        TITLE,
        /** 帳號。 */
        USERNAME,
        /** 密碼（預設遮罩）。 */
        PASSWORD,
        /** 網址。 */
        URL,
        /** 備註。 */
        NOTES,
        /** 自訂欄位（{@link Row#name()} 是欄位名稱）。 */
        CUSTOM,
        /** 所在群組（值是群組路徑）。 */
        GROUP,
        /** 標籤。 */
        TAGS,
        /** 附件（{@link Row#name()} 是附件名稱）。 */
        ATTACHMENT,
        /** 圖示。 */
        ICON,
        /** 到期設定。 */
        EXPIRY
    }

    /**
     * 一筆衝突的條目。
     *
     * @param entryId        條目
     * @param title          條目名稱（這台裝置的；這台沒有名稱時用另一台的）
     * @param localModified  這台裝置的修改時間
     * @param remoteModified 另一台裝置的修改時間
     * @param rows           衝突的欄位（依 {@link Kind} 的順序，同種依名稱）
     */
    public record Item(UUID entryId, String title, Instant localModified, Instant remoteModified, List<Row> rows) {

        /**
         * 複製成不可修改的版本。
         */
        public Item {
            rows = List.copyOf(rows);
        }

        @Override
        public String toString() {
            return "ConflictView.Item[" + entryId + "]";
        }
    }

    /**
     * 一個衝突的欄位。
     *
     * @param key        欄位鍵（{@link Conflict.Field#key()}，傳回 {@link MergeResult#resolve} 用）
     * @param kind       欄位種類
     * @param name       自訂欄位或附件的名稱；其他種類為 {@code null}
     * @param local      這台裝置的值
     * @param remote     另一台裝置的值
     * @param localTime  這台裝置改這一欄的時間（所在群組是 {@code LocationChanged}，其他是條目的修改時間：KDBX 沒有逐欄的時間）
     * @param remoteTime 另一台裝置改這一欄的時間
     * @param secret     預設遮罩、可切換顯示：密碼，以及任一邊受保護（{@code Protected="True"}）的欄位（T-16）
     */
    public record Row(String key, Kind kind, String name, Shown local, Shown remote, Instant localTime,
                      Instant remoteTime, boolean secret) {

        @Override
        public String toString() {
            return "ConflictView.Row[***]";
        }
    }

    /**
     * 一邊的值要怎麼說。
     *
     * @param form 說法
     * @param text 要填進說法的文字（{@link Form#TEXT} 的值本身、群組路徑、圖示編號、附件雜湊的前幾碼）；其他為 {@code null}
     * @param time {@link Form#EXPIRES} 的到期時間；其他為 {@code null}
     */
    public record Shown(Form form, String text, Instant time) {

        /** 說法；平台的字串資源各有一句。 */
        public enum Form {
            /** 直接顯示 {@link Shown#text()}。 */
            TEXT,
            /** 值是空的（「（空白）」）。 */
            EMPTY,
            /** 這一邊刪掉了這個欄位或附件（「（已刪除）」）。 */
            REMOVED,
            /** 群組路徑（{@link Shown#text()}）。 */
            GROUP,
            /** 群組已不存在（「（群組已不存在）」）。 */
            UNKNOWN_GROUP,
            /** 標準圖示，{@link Shown#text()} 是編號。 */
            ICON,
            /** 自訂圖示。 */
            CUSTOM_ICON,
            /** 附件內容，{@link Shown#text()} 是 SHA-256 的前 8 碼。 */
            ATTACHMENT,
            /** 不到期。 */
            NEVER_EXPIRES,
            /** 在 {@link Shown#time()} 到期。 */
            EXPIRES
        }

        @Override
        public String toString() {
            return "Shown[" + form + "]";
        }
    }

    /**
     * @param result 有衝突的合併結果（{@code VaultSession.conflict()}）
     * @return 對照畫面的內容；沒有衝突時 {@code items} 是空的
     */
    public static ConflictView of(MergeResult result) {
        VaultSnapshot local = result.localSnapshot();
        VaultSnapshot remote = result.remoteSnapshot();
        List<Item> items = new ArrayList<>();
        for (Conflict c : result.conflicts()) {
            EntrySnapshot mine = local.entries().get(c.entryId());
            EntrySnapshot theirs = remote.entries().get(c.entryId());
            List<Row> rows = new ArrayList<>();
            for (Conflict.Field f : c.fields()) {
                rows.add(row(f, mine, theirs, local, remote, c));
            }
            rows.sort(Comparator.comparing(Row::kind).thenComparing(r -> r.name() == null ? "" : r.name()));
            items.add(new Item(c.entryId(), title(mine, theirs), c.localModified(), c.remoteModified(), rows));
        }
        return new ConflictView(items);
    }

    @Override
    public String toString() {
        return "ConflictView[" + items.size() + " 筆]";
    }

    private static Row row(Conflict.Field f, EntrySnapshot mine, EntrySnapshot theirs, VaultSnapshot local,
                           VaultSnapshot remote, Conflict c) {
        String key = f.key();
        if (key.equals(Conflict.GROUP)) {
            return new Row(key, Kind.GROUP, null, group(f.localValue(), local, remote),
                    group(f.remoteValue(), remote, local), time(mine, c.localModified()),
                    time(theirs, c.remoteModified()), false);
        }
        Kind kind;
        String name = null;
        if (key.startsWith(EntrySnapshot.STRING_PREFIX)) {
            String field = key.substring(EntrySnapshot.STRING_PREFIX.length());
            int standard = STANDARD.indexOf(field);
            kind = standard < 0 ? Kind.CUSTOM : Kind.values()[standard];
            name = standard < 0 ? field : null;
        } else if (key.startsWith(EntrySnapshot.ATTACHMENT_PREFIX)) {
            kind = Kind.ATTACHMENT;
            name = key.substring(EntrySnapshot.ATTACHMENT_PREFIX.length());
        } else if (key.equals(EntrySnapshot.TAGS)) {
            kind = Kind.TAGS;
        } else if (key.equals(EntrySnapshot.ICON)) {
            kind = Kind.ICON;
        } else if (key.equals(EntrySnapshot.EXPIRY)) {
            kind = Kind.EXPIRY;
        } else {
            // 合併引擎之後加了新的欄位鍵：當成自訂欄位直接顯示，畫面仍能選
            kind = Kind.CUSTOM;
            name = key;
        }
        // 只要有一邊受保護就遮罩：另一邊沒保護不代表這個值可以直接顯示
        boolean secret = kind == Kind.PASSWORD || isProtected(mine, key) || isProtected(theirs, key);
        return new Row(key, kind, name, value(kind, f.localValue()), value(kind, f.remoteValue()),
                c.localModified(), c.remoteModified(), secret);
    }

    private static boolean isProtected(EntrySnapshot e, String key) {
        return e != null && e.isProtected(key);
    }

    private static Instant time(EntrySnapshot e, Instant fallback) {
        return e == null || e.locationChanged() == null ? fallback : e.locationChanged();
    }

    private static Shown value(Kind kind, String raw) {
        if (raw == null) {
            return new Shown(Shown.Form.REMOVED, null, null);
        }
        switch (kind) {
            case ATTACHMENT:
                return new Shown(Shown.Form.ATTACHMENT, raw.substring(0, Math.min(HASH_DIGITS, raw.length())), null);
            case ICON:
                // 「標準圖示編號」或「編號/自訂圖示 UUID」（EntrySnapshot.ICON）
                return raw.indexOf('/') >= 0 ? new Shown(Shown.Form.CUSTOM_ICON, null, null)
                        : new Shown(Shown.Form.ICON, raw, null);
            case EXPIRY:
                return expiry(raw);
            default:
                return raw.isEmpty() ? new Shown(Shown.Form.EMPTY, null, null) : new Shown(Shown.Form.TEXT, raw, null);
        }
    }

    private static Shown expiry(String raw) {
        // 「off」或「on:<自 1970 起的秒數>」（EntrySnapshot.EXPIRY）
        if (raw.startsWith("on:")) {
            try {
                return new Shown(Shown.Form.EXPIRES, null, Instant.ofEpochSecond(Long.parseLong(raw.substring(3))));
            } catch (NumberFormatException e) {
                return new Shown(Shown.Form.TEXT, raw, null);
            }
        }
        return new Shown(Shown.Form.NEVER_EXPIRES, null, null);
    }

    // 群組先在這一邊的快照找，找不到（例如另一台剛建立、這台還沒有）再到另一邊找
    private static Shown group(String raw, VaultSnapshot own, VaultSnapshot other) {
        UUID id;
        try {
            id = UUID.fromString(raw);
        } catch (IllegalArgumentException | NullPointerException e) {
            return new Shown(Shown.Form.UNKNOWN_GROUP, null, null);
        }
        String path = path(id, own);
        if (path == null) {
            path = path(id, other);
        }
        return path == null ? new Shown(Shown.Form.UNKNOWN_GROUP, null, null) : new Shown(Shown.Form.GROUP, path, null);
    }

    // 同 EntryQuery.groupPath：根群組（保管庫名稱）到這個群組，以「 / 」連接
    private static String path(UUID id, VaultSnapshot snapshot) {
        if (!snapshot.groups().containsKey(id)) {
            return null;
        }
        List<String> names = new ArrayList<>();
        UUID g = id;
        // 有上限：檔案損壞造成的循環不會讓這裡停不下來
        for (int i = 0; g != null && i <= snapshot.groups().size(); i++) {
            GroupSnapshot group = snapshot.groups().get(g);
            if (group == null) {
                break;
            }
            names.add(Objects.toString(group.fields().get(GroupSnapshot.NAME), ""));
            g = group.parentId();
        }
        Collections.reverse(names);
        return String.join(" / ", names);
    }

    private static String title(EntrySnapshot mine, EntrySnapshot theirs) {
        String t = mine == null ? null : mine.fields().get(TITLE);
        if (t == null || t.isEmpty()) {
            t = theirs == null ? null : theirs.fields().get(TITLE);
        }
        return t == null ? "" : t;
    }
}
