package app.svault.core.module;

import java.util.UUID;

/**
 * {@code ModuleHost.addEntries}（T-33）的結果。只有 {@link Status#SAVED} 時保管庫有改變；其他情況一筆都沒有加入
 * （不留下半批），模組照 {@link #status()} 顯示訊息、讓使用者稍後再試。
 *
 * @param status  結果
 * @param groupId 新群組的 UUID（{@link Status#SAVED} 時；其他為 {@code null}）
 * @param added   加入的筆數（{@link Status#SAVED} 時是整批的筆數；其他為 0）
 * @param merged  存檔前合併了另一台裝置的修改（{@link Status#SAVED} 時才可能為 {@code true}；外殼的同步提示照常顯示）
 */
public record EntryBatchResult(Status status, UUID groupId, int added, boolean merged) {

    /** 結果的種類。 */
    public enum Status {
        /** 整批已加入並寫進保管庫檔案。 */
        SAVED,
        /** 已上鎖（含按下上鎖之後、還沒解鎖過）：沒有加入。 */
        LOCKED,
        /** 保管庫有衝突要先處理（原本就有，或存檔時和另一台裝置的修改衝突）：沒有加入。 */
        CONFLICT,
        /** 無法存檔（離線、權限被撤銷、讀回驗證失敗等）：沒有加入，保管庫維持原狀。 */
        FAILED
    }

    /**
     * @param groupId 新群組
     * @param added   筆數
     * @param merged  合併了另一台裝置的修改
     * @return 成功的結果
     */
    public static EntryBatchResult saved(UUID groupId, int added, boolean merged) {
        return new EntryBatchResult(Status.SAVED, groupId, added, merged);
    }

    /**
     * @param status 不是 {@link Status#SAVED} 的結果
     * @return 沒有加入任何條目的結果
     */
    public static EntryBatchResult rejected(Status status) {
        if (status == Status.SAVED) {
            throw new IllegalArgumentException("SAVED 要用 saved(…)");
        }
        return new EntryBatchResult(status, null, 0, false);
    }

    /** @return 整批已加入 */
    public boolean saved() {
        return status == Status.SAVED;
    }
}
