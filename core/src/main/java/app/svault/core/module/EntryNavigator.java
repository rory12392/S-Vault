package app.svault.core.module;

import java.util.UUID;

/**
 * 外殼提供給功能模組的導覽服務：以 UUID 開啟條目詳細（例如 HLT 的「跳到條目」）。由兩平台外殼實作（T-30），
 * 模組從外殼的 {@code ModuleHost} 取得（desktop {@code host.navigator()}、android
 * {@code ModuleHost.of(context).navigator(requireActivity())}）。
 *
 * <p>外殼的處理（Architecture §6，判斷在 {@code core.shell.EntryTarget}）：
 * <ul>
 *   <li>一般條目、資源回收桶裡的條目：開條目詳細（回收桶裡的顯示「還原」「永久刪除」）。desktop 關掉模組頁、回到三欄並選到
 *       條目（編輯中有未儲存的修改時先確認「捨棄修改？」）；android 把詳細疊在模組頁上，返回回到模組頁。</li>
 *   <li>保管庫裡沒有這個 UUID：不換畫面，顯示 error 提示「找不到這筆條目，它可能已被刪除」。</li>
 *   <li>已上鎖或內容還在載入：不做事（上鎖時外殼本來就會換掉模組頁、導回解鎖畫面）。</li>
 * </ul>
 * 都不丟例外。可以在任何執行緒呼叫，外殼轉到 UI 執行緒處理。
 */
@FunctionalInterface
public interface EntryNavigator {

    /**
     * 開啟條目詳細畫面。
     *
     * @param entryId 條目的 KDBX UUID；條目不存在（含 {@code null}）時外殼顯示提示，不丟例外
     */
    void openEntry(UUID entryId);
}
