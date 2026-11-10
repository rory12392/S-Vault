package app.svault.core.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * {@code base/}、{@code pending/} 的檔案（Architecture §2.2）。只由 {@link SaveCoordinator} 使用（T-27 從它拆出）。
 *
 * <p>檔名是保管庫 ID＋位置（{@link LocalStore#baseFile}）。T-27 之前只用保管庫 ID：第一次以新檔名讀不到時，把舊檔名的
 * 檔案搬過來（{@link #adoptLegacy}；只搬確定屬於這個位置的，其他的原樣留著）。
 */
final class SyncFiles {

    private static final Logger LOG = Logger.getLogger(SyncFiles.class.getName());

    private final LocalStore store;
    private final RecentStore recent;

    SyncFiles(LocalStore store, RecentStore recent) {
        this.store = store;
        this.recent = recent;
    }

    Path base(UUID vaultId, VaultLocation location) {
        return store.baseFile(vaultId, location.persistentId());
    }

    Path pending(UUID vaultId, VaultLocation location) {
        return store.pendingFile(vaultId, location.persistentId());
    }

    Path conflict(UUID vaultId, VaultLocation location) {
        return store.conflictFile(vaultId, location.persistentId());
    }

    byte[] readBase(UUID vaultId, VaultLocation location) {
        adoptLegacy(vaultId, location);
        return read(base(vaultId, location), "基準");
    }

    byte[] readPending(UUID vaultId, VaultLocation location) {
        adoptLegacy(vaultId, location);
        return read(pending(vaultId, location), "本機暫存");
    }

    void setAsidePending(UUID vaultId, VaultLocation location) {
        Path pending = pending(vaultId, location);
        try {
            Files.move(pending, pending.resolveSibling(pending.getFileName() + ".unusable"),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "無法移開解不開的本機暫存：{0}", e.getClass().getName());
        }
    }

    /**
     * 升級（T-27）：把只以保管庫 ID 命名的 {@code base/<ID>.kdbx}、{@code pending/<ID>.kdbx} 搬成這個位置的檔名。
     * 屬於這個位置的條件：{@code recent.json} 裡這個保管庫 ID 只有這個位置（或沒有任何紀錄），或舊基準的 SHA-256 等於
     * 這個位置記下的遠端雜湊（兩個位置共用 ID 時，舊基準是最後同步的那一個寫的）。不屬於的原樣留著，給另一個位置用；
     * 都不屬於時也不刪（{@code pending/} 可能有還沒寫出去的修改，留著可以手動救回）。
     *
     * <p>升級只新增這一段，不改既有的檔案內容（STACK §3）；搬移失敗時下次讀取再試，這次當成沒有基準（合併不遺失資料）。
     */
    private void adoptLegacy(UUID vaultId, VaultLocation location) {
        Path legacyBase = store.legacyBaseFile(vaultId);
        Path legacyPending = store.legacyPendingFile(vaultId);
        boolean hasBase = Files.exists(legacyBase);
        if (!hasBase && !Files.exists(legacyPending)) {
            return;
        }
        if (!ownsLegacy(vaultId, location.persistentId(), hasBase ? read(legacyBase, "舊基準") : null)) {
            return;
        }
        move(legacyBase, base(vaultId, location));
        move(legacyPending, pending(vaultId, location));
    }

    private boolean ownsLegacy(UUID vaultId, String location, byte[] legacyBase) {
        boolean onlyHere = true;
        String recordedHash = null;
        for (RecentVault v : recent.list()) {
            if (!v.vaultId().equals(vaultId)) {
                continue;
            }
            if (v.location().equals(location)) {
                recordedHash = v.remoteHash();
            } else {
                onlyHere = false;
            }
        }
        return onlyHere || legacyBase != null && Hashes.sha256(legacyBase).equals(recordedHash);
    }

    private static void move(Path from, Path to) {
        if (!Files.exists(from) || Files.exists(to)) {
            return;
        }
        try {
            Files.createDirectories(to.getParent());
            Files.move(from, to);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "無法搬移舊的本機同步檔：{0}", e.getClass().getName());
        }
    }

    static byte[] read(Path file, String what) {
        try {
            return BoundedRead.file(file, VaultLocation.MAX_BYTES);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException | VaultIoException e) {
            LOG.log(Level.WARNING, "無法讀取{0}：{1}", new Object[] {what, e.getClass().getName()});
            return null;
        }
    }
}
