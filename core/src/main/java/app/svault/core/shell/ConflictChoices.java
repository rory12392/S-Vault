package app.svault.core.shell;

import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 衝突對照畫面上使用者的選擇（AC-08-2、AC-08-4），兩平台共用（T-15 建立，T-16 沿用）：每筆條目、每個
 * 欄位選了哪一邊，「全部用這台／另一台裝置的」，以及「下一筆」「完成並儲存」前要檢查的「還有欄位沒有選擇」。
 *
 * <p>畫面開啟時可以帶入上一次的選擇（例如上次按「完成並儲存」時離線，衝突還在）：只有同一筆條目、同一個欄位、而且兩邊
 * 的值都沒變時才沿用，遠端又改過的欄位要重新選。只在畫面執行緒使用。
 */
public final class ConflictChoices {

    private final ConflictView view;
    private final Map<UUID, Map<String, Side>> chosen = new HashMap<>();

    /**
     * @param view 對照畫面的內容
     */
    public ConflictChoices(ConflictView view) {
        this(view, null, null);
    }

    /**
     * 帶入已經比對過的選擇（AC-08-5：上鎖前的選擇，由 {@code ConflictDraft} 篩掉值變了的欄位）。
     *
     * @param view   對照畫面的內容
     * @param preset 要帶入的選擇；不在畫面上的條目或欄位略過
     */
    public ConflictChoices(ConflictView view, Map<UUID, Map<String, Side>> preset) {
        this.view = view;
        for (ConflictView.Item item : view.items()) {
            Map<String, Side> before = preset.get(item.entryId());
            if (before == null) {
                continue;
            }
            for (ConflictView.Row row : item.rows()) {
                Side side = before.get(row.key());
                if (side != null) {
                    choose(item.entryId(), row.key(), side);
                }
            }
        }
    }

    /**
     * @param view         對照畫面的內容
     * @param previousView 上一次開啟時的內容；沒有時 {@code null}
     * @param previous     上一次的選擇（{@link #toMap()}）；沒有時 {@code null}
     */
    public ConflictChoices(ConflictView view, ConflictView previousView, Map<UUID, Map<String, Side>> previous) {
        this.view = view;
        if (previousView == null || previous == null) {
            return;
        }
        for (ConflictView.Item item : view.items()) {
            Map<String, Side> before = previous.get(item.entryId());
            ConflictView.Item old = find(previousView, item.entryId());
            if (before == null || old == null) {
                continue;
            }
            for (ConflictView.Row row : item.rows()) {
                Side side = before.get(row.key());
                if (side != null && unchanged(row, old)) {
                    choose(item.entryId(), row.key(), side);
                }
            }
        }
    }

    /** @return 對照畫面的內容 */
    public ConflictView view() {
        return view;
    }

    /**
     * @param entryId 條目
     * @param key     欄位鍵
     * @return 選了哪一邊；還沒選時 {@code null}
     */
    public Side get(UUID entryId, String key) {
        Map<String, Side> m = chosen.get(entryId);
        return m == null ? null : m.get(key);
    }

    /**
     * @param entryId 條目
     * @param key     欄位鍵
     * @param side    選哪一邊；{@code null} 清除
     */
    public void choose(UUID entryId, String key, Side side) {
        if (side == null) {
            Map<String, Side> m = chosen.get(entryId);
            if (m != null) {
                m.remove(key);
            }
            return;
        }
        chosen.computeIfAbsent(entryId, k -> new LinkedHashMap<>()).put(key, side);
    }

    /**
     * 整筆選同一邊（「全部用這台裝置的」「全部用另一台裝置的」）。
     *
     * @param index 第幾筆（從 0 起）
     * @param side  哪一邊
     */
    public void chooseAll(int index, Side side) {
        ConflictView.Item item = view.items().get(index);
        for (ConflictView.Row row : item.rows()) {
            choose(item.entryId(), row.key(), side);
        }
    }

    /**
     * @param index 第幾筆（從 0 起）
     * @return 這一筆第一個還沒選的欄位（畫面順序）；都選了時為空
     */
    public Optional<ConflictView.Row> firstUnchosen(int index) {
        ConflictView.Item item = view.items().get(index);
        for (ConflictView.Row row : item.rows()) {
            if (get(item.entryId(), row.key()) == null) {
                return Optional.of(row);
            }
        }
        return Optional.empty();
    }

    /** @return 第一筆還有欄位沒選的條目（從 0 起）；全部選完時 -1 */
    public int firstIncomplete() {
        for (int i = 0; i < view.items().size(); i++) {
            if (firstUnchosen(i).isPresent()) {
                return i;
            }
        }
        return -1;
    }

    /** @return 每筆、每個欄位都選了 */
    public boolean complete() {
        return firstIncomplete() < 0;
    }

    /**
     * @return 傳給 {@code VaultSession.resolve} 的選擇（只含畫面上的條目與欄位；可能還沒選完，先看 {@link #complete()}）
     */
    public Map<UUID, Map<String, Side>> toMap() {
        Map<UUID, Map<String, Side>> out = new LinkedHashMap<>();
        for (ConflictView.Item item : view.items()) {
            Map<String, Side> m = new LinkedHashMap<>();
            for (ConflictView.Row row : item.rows()) {
                Side side = get(item.entryId(), row.key());
                if (side != null) {
                    m.put(row.key(), side);
                }
            }
            out.put(item.entryId(), Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableMap(out);
    }

    @Override
    public String toString() {
        return "ConflictChoices[" + view.items().size() + " 筆]";
    }

    private static ConflictView.Item find(ConflictView v, UUID entryId) {
        for (ConflictView.Item item : v.items()) {
            if (item.entryId().equals(entryId)) {
                return item;
            }
        }
        return null;
    }

    private static boolean unchanged(ConflictView.Row row, ConflictView.Item old) {
        for (ConflictView.Row r : old.rows()) {
            if (r.key().equals(row.key())) {
                return r.local().equals(row.local()) && r.remote().equals(row.remote());
            }
        }
        return false;
    }
}
