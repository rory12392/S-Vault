package app.svault.core.shell;

import app.svault.core.vault.EntryView;
import java.util.UUID;

/**
 * {@code EntryNavigator}（以 UUID 開啟條目詳細，T-30）要開的是什麼：兩平台外殼依同一套判斷決定開詳細、顯示「找不到」
 * 或不做事（Architecture §6）。
 *
 * @param kind  判斷結果
 * @param entry 要開的條目；{@link Kind#NOT_FOUND}、{@link Kind#UNAVAILABLE} 時為 {@code null}
 */
public record EntryTarget(Kind kind, EntryView entry) {

    /** 判斷結果。 */
    public enum Kind {
        /** 在一般群組（含根群組）：開詳細。 */
        ACTIVE,
        /** 在資源回收桶或回收桶裡的群組：一樣開詳細，詳細頁顯示回收桶的狀態（「還原」「永久刪除」）。 */
        RECYCLED,
        /** 保管庫裡沒有這個 UUID（已永久刪除、被同步拿掉、別的保管庫的）：不換畫面，顯示「找不到這筆條目」。 */
        NOT_FOUND,
        /** 沒有內容可查（已上鎖、解鎖後還在載入）：不做事。 */
        UNAVAILABLE
    }

    /**
     * @param data    目前的內容；已上鎖或還在載入時 {@code null}
     * @param entryId 條目的 KDBX UUID；{@code null} 視為找不到
     * @return 判斷結果
     */
    public static EntryTarget resolve(ScreenData data, UUID entryId) {
        if (data == null) {
            return new EntryTarget(Kind.UNAVAILABLE, null);
        }
        EntryView found = data.entry(entryId).orElse(null);
        if (found == null) {
            return new EntryTarget(Kind.NOT_FOUND, null);
        }
        return new EntryTarget(data.inRecycleBin(found.groupId()) ? Kind.RECYCLED : Kind.ACTIVE, found);
    }

    /** @return 要開詳細（{@link Kind#ACTIVE} 或 {@link Kind#RECYCLED}） */
    public boolean opens() {
        return entry != null;
    }

    /** 條目是使用者的內容：只輸出種類與 UUID（{@link EntryView#toString()} 本來就只有 UUID，這裡不依賴它）。 */
    @Override
    public String toString() {
        return "EntryTarget[" + kind + (entry == null ? "" : ", " + entry.uuid()) + "]";
    }
}
