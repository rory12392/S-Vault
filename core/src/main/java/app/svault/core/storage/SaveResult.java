package app.svault.core.storage;

import java.nio.file.Path;

/**
 * 一次成功存檔的結果。
 *
 * @param remoteHash         寫入遠端的位元組的 SHA-256（小寫十六進位）
 * @param backup             這次存檔前的備份；遠端原本不存在時為 {@code null}
 * @param localStateUpdated  {@code base/} 與 {@code recent.json} 都已更新。{@code false} 時遠端已經寫好，
 *                           只是本機狀態沒跟上（下次解鎖會當成遠端有變更再比對一次，不會遺失資料）
 */
public record SaveResult(String remoteHash, Path backup, boolean localStateUpdated) {
}
