package app.svault.core.vault;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 三方合併用的條目快照（Architecture §4 第 2 點的比較欄位）。由 {@link Vault#snapshot()} 建立，之後保管庫再改也不會變。
 *
 * <p>{@link #fields()} 把要逐欄比較的內容攤平成「欄位鍵 → 字串」：每個字串欄位（標準五欄與自訂欄位）、每個附件
 * （名稱＋內容的 SHA-256）、標籤、圖示、到期設定。所在群組另外放在 {@link #groupId()}（它的時間是
 * {@code LocationChanged}，和其他欄位的 {@code LastModificationTime} 不同）。欄位鍵的格式由這個類別的常數與
 * {@link #stringKey}、{@link #attachmentKey} 決定，合併結果與衝突清單都用同一組鍵。
 *
 * <p>受保護與否（{@code Protected="True"}）不算內容，合併時不比較（套用時沿用來源欄位的保護狀態）；
 * {@link #protectedKeys()} 只給衝突對照畫面決定哪些欄位要遮罩（T-16）。
 *
 * @param uuid            條目 UUID
 * @param groupId         所在群組
 * @param fields          逐欄比較的內容（鍵依字母排序，不可修改；沒有的欄位不在裡面）
 * @param protectedKeys   受保護的字串欄位的鍵（{@link #stringKey}；不可修改）
 * @param lastModified    最後修改時間
 * @param locationChanged 最後一次移動群組的時間
 * @param historyTimes    歷史版本的修改時間（最舊的在前，不可修改；合併時以它聯集、去重）
 */
public record EntrySnapshot(UUID uuid, UUID groupId, Map<String, String> fields, Set<String> protectedKeys,
                            Instant lastModified, Instant locationChanged, List<Instant> historyTimes) {

    /** 字串欄位鍵的前綴，後面接欄位名稱（例：{@code s:Password}）。 */
    public static final String STRING_PREFIX = "s:";
    /** 附件鍵的前綴，後面接附件名稱；值是內容的 SHA-256（十六進位）。 */
    public static final String ATTACHMENT_PREFIX = "b:";
    /** 標籤。 */
    public static final String TAGS = "tags";
    /** 圖示：標準圖示編號，有自訂圖示時加上「/UUID」。 */
    public static final String ICON = "icon";
    /** 到期設定：不到期為 {@code off}，到期為 {@code on:<自 1970 起的秒數>}。 */
    public static final String EXPIRY = "expiry";

    /**
     * 複製集合成不可修改的版本。
     */
    public EntrySnapshot {
        fields = Collections.unmodifiableMap(new TreeMap<>(fields));
        protectedKeys = Collections.unmodifiableSet(new TreeSet<>(protectedKeys));
        historyTimes = List.copyOf(historyTimes);
    }

    /**
     * @param name 字串欄位名稱，例如 {@code Password} 或自訂欄位的名稱
     * @return 欄位鍵
     */
    public static String stringKey(String name) {
        return STRING_PREFIX + name;
    }

    /**
     * @param name 附件名稱
     * @return 欄位鍵
     */
    public static String attachmentKey(String name) {
        return ATTACHMENT_PREFIX + name;
    }

    /**
     * @param key 欄位鍵
     * @return 這個欄位在這一邊受保護（KeePassXC 的「保護」；密碼欄通常是）
     */
    public boolean isProtected(String key) {
        return protectedKeys.contains(key);
    }

    /**
     * 條目內容不能因為被印出或寫進日誌而外洩（CONVENTIONS §2）。
     */
    @Override
    public String toString() {
        return "EntrySnapshot[" + uuid + "]";
    }
}
