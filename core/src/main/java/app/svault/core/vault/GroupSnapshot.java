package app.svault.core.vault;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 三方合併用的群組快照（Architecture §4 第 4 點：名稱、備註、圖示、所在群組）。
 *
 * @param uuid            群組 UUID
 * @param parentId        上層群組；根群組為 {@code null}
 * @param fields          逐欄比較的內容（{@link #NAME}、{@link #NOTES}、{@link #ICON}；不可修改）
 * @param lastModified    最後修改時間
 * @param locationChanged 最後一次移動的時間
 */
public record GroupSnapshot(UUID uuid, UUID parentId, Map<String, String> fields, Instant lastModified,
                            Instant locationChanged) {

    /** 名稱。 */
    public static final String NAME = "name";
    /** 備註。 */
    public static final String NOTES = "notes";
    /** 圖示（格式同 {@link EntrySnapshot#ICON}）。 */
    public static final String ICON = "icon";

    /**
     * 複製成不可修改的版本。
     */
    public GroupSnapshot {
        fields = Collections.unmodifiableMap(new TreeMap<>(fields));
    }

    /**
     * 群組名稱也是使用者的內容，只輸出 UUID。
     */
    @Override
    public String toString() {
        return "GroupSnapshot[" + uuid + "]";
    }
}
