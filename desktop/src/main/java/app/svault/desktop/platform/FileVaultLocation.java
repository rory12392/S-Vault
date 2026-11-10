package app.svault.desktop.platform;

import app.svault.core.storage.AtomicFiles;
import app.svault.core.storage.VaultIoException;
import app.svault.core.storage.VaultLocation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * PC 上的保管庫位置：Drive 電腦版同步資料夾裡的 {@code .kdbx}（AC-06-1）。寫入是同資料夾暫存檔＋讀回比對＋
 * {@code ATOMIC_MOVE} 取代（{@link AtomicFiles}，AC-09-3）。
 */
public final class FileVaultLocation implements VaultLocation {

    private final Path file;
    private final AtomicFiles.Probe probe;

    /**
     * @param file 保管庫檔案
     */
    public FileVaultLocation(Path file) {
        this(file, stage -> { });
    }

    /** 故障注入測試用：寫入過程的每個檢查點通知 {@code probe}。 */
    FileVaultLocation(Path file, AtomicFiles.Probe probe) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.probe = Objects.requireNonNull(probe, "probe");
    }

    @Override
    public String displayName() {
        return file.getFileName().toString();
    }

    @Override
    public String persistentId() {
        return file.toString();
    }

    @Override
    public boolean exists() {
        return Files.isRegularFile(file);
    }

    @Override
    public byte[] readAll() throws VaultIoException {
        try (InputStream in = Files.newInputStream(file)) {
            return VaultLocation.readBounded(in, displayName());
        } catch (IOException e) {
            throw translate("讀取", e);
        }
    }

    @Override
    public void writeAll(byte[] data) throws VaultIoException {
        try {
            AtomicFiles.write(file, data, probe);
        } catch (IOException e) {
            throw translate("寫入", e);
        }
    }

    @Override
    public boolean supportsAtomicReplace() {
        return true;
    }

    private VaultIoException translate(String action, IOException e) {
        VaultIoException.Reason reason;
        if (e instanceof NoSuchFileException) {
            reason = VaultIoException.Reason.NOT_FOUND;
        } else if (e instanceof AccessDeniedException) {
            reason = VaultIoException.Reason.ACCESS_DENIED;
        } else {
            reason = VaultIoException.Reason.IO;
        }
        // 只記動作、例外類型與檔名，不記完整路徑以外的內容
        return new VaultIoException(reason, action + "失敗：" + e.getClass().getSimpleName() + "（" + displayName() + "）");
    }
}
