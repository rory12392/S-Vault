package app.svault.core.storage;

import app.svault.core.vault.Vault;
import java.nio.file.Path;

/**
 * 一次成功還原的結果（Architecture §5「備份還原」）。
 *
 * @param vault             以還原後的內容開啟的保管庫；呼叫端接手（交給 {@code VaultSession}），不用時要 {@code close()}
 * @param remoteHash        寫入遠端的位元組的 SHA-256（小寫十六進位）
 * @param safetyBackup      還原前替遠端目前內容做的備份；遠端原本不存在時為 {@code null}
 * @param localStateUpdated {@code base/} 與 {@code recent.json} 都已更新（同 {@link SaveResult#localStateUpdated()}）
 */
public record RestoreResult(Vault vault, String remoteHash, Path safetyBackup, boolean localStateUpdated) {
}
