package app.svault.core.sync;

import java.util.UUID;

/**
 * 合併時要讓使用者知道的事（不是錯誤，合併照常完成）。文字由平台依 {@link Kind} 從自己的字串資源產生
 * （CONVENTIONS §4），這裡的說明是 PRD／Architecture 的原意。
 *
 * @param kind   種類
 * @param itemId 相關的條目或群組；{@link Kind#FIRST_SYNC} 為 {@code null}
 */
public record MergeNotice(Kind kind, UUID itemId) {

    /** 通知的種類。 */
    public enum Kind {
        /** 另一台裝置刪除了這筆條目，這台有修改，已保留修改（AC-07-4：「某筆條目在另一台裝置被刪除，已保留你的修改」）。 */
        KEPT_LOCAL_EDIT,
        /** 這台裝置刪除了這筆條目，另一台有修改，已保留另一台修改後的版本（AC-07-4 的反方向）。 */
        KEPT_REMOTE_EDIT,
        /** 一邊刪除了群組、另一邊在裡面新增或修改了東西（或改了群組本身），已保留這個群組（Architecture §4 第 4 點）。 */
        GROUP_KEPT,
        /** 所在群組在合併結果中不存在，條目或群組已移到根群組。 */
        MOVED_TO_ROOT,
        /** 兩邊都改了群組的同一個欄位且不同（或移到不同地方），已以較新的為準（群組沒有歷史紀錄，不進衝突清單）。 */
        GROUP_NEWER_KEPT,
        /** 沒有合併基準（這台裝置第一次同步、基準遺失或保管庫 ID 不符），已以較新的版本為準，舊版本保留在歷史紀錄（第 6 點）。 */
        FIRST_SYNC,
        /**
         * 遠端被不含這台修改的版本整份取代（例：Google Drive 恢復同步時以另一台的版本覆蓋雲端），基準不能用：兩邊的條目都
         * 保留，同一筆以較新的為準、較舊的保留在歷史紀錄（AC-07-9；第 4 點的取代偵測、第 6 點的規則）。
         */
        REMOTE_REPLACED,
        /**
         * 沒有基準可用時（第 4、6 點），這筆條目只在一邊，但另一邊的刪除紀錄（{@code DeletedObjects}）時間比它最後修改的
         * 時間晚：已當成另一邊刪除，一併刪除（T-99，D-190；KeePass 2 的同步做法）。兩台時鐘差很多時可能誤判，所以列出來。
         */
        DELETED_BY_RECORD
    }
}
