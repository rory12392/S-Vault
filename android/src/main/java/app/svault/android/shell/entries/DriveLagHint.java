package app.svault.android.shell.entries;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * 每次解鎖第一次存檔成功時，「已儲存」後面多說一次 Google Drive 可能有幾分鐘延遲（D-191；P-86、P-87）。手機看不出
 * Drive 的快取是不是舊的（快取舊時檔案的修改時間也是舊的），所以不是偵測到才提示，而是每次解鎖提醒一次。
 * 以這次解鎖的 {@link EntriesModel} 為鍵（上鎖後丟掉，弱參照不留住它）。只在主執行緒用。
 */
final class DriveLagHint {

    private static final Set<EntriesModel> SHOWN = Collections.newSetFromMap(new WeakHashMap<>());

    private DriveLagHint() {
    }

    /**
     * @param model 這次解鎖的條目狀態
     * @return 這次解鎖第一次呼叫時為 {@code true}
     */
    static boolean takeFirst(EntriesModel model) {
        return SHOWN.add(model);
    }
}
