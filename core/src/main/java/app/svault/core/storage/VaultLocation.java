package app.svault.core.storage;

import java.io.IOException;
import java.io.InputStream;

/**
 * 保管庫檔案所在的位置（Architecture §6）：PC 是 Drive 電腦版資料夾裡的檔案（{@code FileVaultLocation}），
 * Android 是檔案選擇器給的 Uri（{@code SafVaultLocation}，T-06）。
 *
 * <p>只搬位元組，不解密。寫入只能由 {@link SaveCoordinator} 呼叫（CLAUDE.md 安全底線）：它在寫入前已完成備份與讀回驗證。
 * 方法是同步的 I/O，呼叫端放在背景執行緒。
 */
public interface VaultLocation {

    /**
     * 能開啟的保管庫檔案大小上限（256 MiB）。超過時 {@link #readAll()} 丟 {@link VaultIoException.Reason#TOO_LARGE}，
     * 不把整個檔案讀進記憶體：被換成數 GB 檔案的保管庫不會讓 App 記憶體耗盡（2026-11-03 資安總檢 L-1）。
     */
    long MAX_BYTES = 256L * 1024 * 1024;

    /**
     * 讀完整個串流，超過 {@link #MAX_BYTES} 就停止（平台的 {@link #readAll()} 共用）。不關閉串流。
     *
     * @param in          檔案內容
     * @param displayName 給錯誤訊息用的檔名
     * @return 全部位元組
     * @throws IOException      讀取失敗
     * @throws VaultIoException 超過上限（{@link VaultIoException.Reason#TOO_LARGE}）
     */
    static byte[] readBounded(InputStream in,String displayName) throws IOException, VaultIoException {
        return BoundedRead.read(in, displayName, MAX_BYTES);
    }

    /**
     * @return 給使用者看的名稱（通常是檔名）
     */
    String displayName();

    /**
     * @return 記在 {@code recent.json} 的位置字串（PC：絕對路徑；Android：Uri 字串），平台用它重建位置
     */
    String persistentId();

    /**
     * @return 檔案存在
     * @throws VaultIoException 無法判斷（例如權限被撤銷）
     */
    boolean exists() throws VaultIoException;

    /**
     * @return 檔案的全部位元組
     * @throws VaultIoException 讀不到（{@link VaultIoException.Reason} 說明原因，AC-06-4），或超過 {@link #MAX_BYTES}
     */
    byte[] readAll() throws VaultIoException;

    /**
     * 以 {@code data} 取代整個檔案。支援原子取代的位置（{@link #supportsAtomicReplace()}）先寫同資料夾的暫存檔、
     * 讀回比對一致後才取代原檔，失敗或中斷時原檔不變（AC-09-3）。
     *
     * @param data 新的檔案內容（已加密的 KDBX）
     * @throws VaultIoException 寫入失敗；原子取代的位置保證原檔仍完整
     */
    void writeAll(byte[] data) throws VaultIoException;

    /**
     * @return {@code true}：寫入是「暫存檔＋原子取代」；{@code false}：整檔覆寫（Android SAF，AC-09-5）
     */
    boolean supportsAtomicReplace();
}
