package app.svault.core.vault;

import java.time.Instant;
import java.util.UUID;

/**
 * KDBX 的 {@code DeletedObjects} 中的一筆刪除紀錄（Architecture §2.1）。三方合併（T-13）用來產生通知，
 * 刪除的判定仍以「基準有、這一邊沒有」為準（Architecture §4 第 4 點）。
 *
 * @param uuid         被刪除的條目或群組的 UUID
 * @param deletionTime 刪除時間
 */
public record DeletedObjectView(UUID uuid, Instant deletionTime) {
}
