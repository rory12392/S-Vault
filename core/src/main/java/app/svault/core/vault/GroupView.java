package app.svault.core.vault;

import java.time.Instant;
import java.util.UUID;

/**
 * 群組的唯讀快照。
 *
 * @param uuid         群組 UUID
 * @param parentId     上層群組的 UUID；根群組為 {@code null}
 * @param name         名稱
 * @param notes        備註（沒有時為空字串）
 * @param recycleBin   是否為資源回收桶
 * @param lastModified 最後修改時間
 */
public record GroupView(UUID uuid, UUID parentId, String name, String notes, boolean recycleBin,
                        Instant lastModified) {

    /**
     * 群組名稱也是使用者的內容，與條目一樣只輸出 UUID。
     */
    @Override
    public String toString() {
        return "GroupView[" + uuid + "]";
    }
}
