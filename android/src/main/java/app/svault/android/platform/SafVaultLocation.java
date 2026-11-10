package app.svault.android.platform;

import app.svault.core.storage.VaultIoException;
import app.svault.core.storage.VaultLocation;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Android 上的保管庫位置：檔案選擇器（{@code ACTION_OPEN_DOCUMENT}）給的文件 Uri，通常是 Google Drive 上的
 * {@code .kdbx}（AC-06-2）。選檔時取得長期權限，App 重新啟動後以 {@code recent.json} 記的 Uri 字串重建。
 *
 * <p>SAF 沒有原子取代（AC-09-5）：{@link #writeAll} 以 {@code "wt"} 整檔覆寫，寫完再讀回比對全部位元組。
 * 寫入前的保護由 {@code SaveCoordinator} 負責：先備份原檔、在記憶體中讀回驗證（D-43），所以這裡不另外在
 * 私有資料夾寫暫存複本。覆寫途中中斷造成的損壞，在下次開啟時由 {@link OpenFailure} 判斷並提示從備份還原。
 *
 * <p>錯誤一律轉成 {@link VaultIoException}（AC-06-4）：權限被撤銷是 {@code ACCESS_DENIED}、文件不存在是
 * {@code NOT_FOUND}、其他（離線且沒有快取、寫到一半、讀回不符）是 {@code IO}。訊息只放顯示名稱，不放 Uri
 * （Drive 的 Uri 含帳號與文件 ID）。
 */
public final class SafVaultLocation implements VaultLocation {

    /** 不知道顯示名稱時用的名稱；不顯示 Uri。 */
    static final String FALLBACK_NAME = "保管庫.kdbx";
    /** 截斷較晚生效時最多再讀幾次、每次間隔（合計最多約 2 秒；Drive 實測 0.1 秒內就一致，T-11）。 */
    private static final int LATE_TRUNCATE_RETRIES = 10;
    private static final long LATE_TRUNCATE_WAIT_MS = 200;

    private final DocumentAccess documents;
    private final String uri;
    private final String displayName;

    SafVaultLocation(DocumentAccess documents, String uri, String displayName) {
        this.documents = Objects.requireNonNull(documents, "documents");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.displayName = displayName == null || displayName.trim().isEmpty() ? FALLBACK_NAME : displayName;
    }

    /**
     * 使用者剛從檔案選擇器選了檔案：取得長期讀寫權限（AC-06-2）並查詢顯示名稱。
     *
     * @param documents 文件存取
     * @param uri       選擇器回傳的 Uri 字串
     * @return 位置
     * @throws VaultIoException 拿不到長期權限（{@code ACCESS_DENIED}）或文件不存在（{@code NOT_FOUND}）
     */
    public static SafVaultLocation adopt(DocumentAccess documents, String uri) throws VaultIoException {
        SafVaultLocation probe = new SafVaultLocation(documents, uri, null);
        try {
            documents.takePersistedPermission(uri);
        } catch (SecurityException e) {
            throw probe.failure(VaultIoException.Reason.ACCESS_DENIED, "取得長期權限", e);
        }
        String name;
        try {
            name = documents.queryDisplayName(uri);
        } catch (SecurityException e) {
            throw probe.failure(VaultIoException.Reason.ACCESS_DENIED, "查詢", e);
        } catch (IOException e) {
            throw probe.failure(VaultIoException.Reason.IO, "查詢", e);
        }
        if (name == null) {
            throw probe.failure(VaultIoException.Reason.NOT_FOUND, "查詢", null);
        }
        return new SafVaultLocation(documents, uri, name);
    }

    /**
     * 從 {@code recent.json} 重建位置（App 重新啟動後，不需重新選檔）。不做 I/O；權限是否還在，第一次讀寫時才知道。
     *
     * @param documents   文件存取
     * @param uri         {@link #persistentId()} 記下的 Uri 字串
     * @param displayName 記下的顯示名稱；{@code null} 時用預設名稱
     * @return 位置
     */
    public static SafVaultLocation reopen(DocumentAccess documents, String uri, String displayName) {
        return new SafVaultLocation(documents, uri, displayName);
    }

    /** 放棄長期權限（從最近開啟清單移除時）。不會刪除檔案。 */
    public void forget() {
        documents.releasePersistedPermission(uri);
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String persistentId() {
        return uri;
    }

    @Override
    public boolean exists() throws VaultIoException {
        requirePermission("查詢");
        try {
            return documents.queryDisplayName(uri) != null;
        } catch (SecurityException e) {
            throw failure(VaultIoException.Reason.ACCESS_DENIED, "查詢", e);
        } catch (IOException e) {
            throw failure(VaultIoException.Reason.IO, "查詢", e);
        }
    }

    @Override
    public byte[] readAll() throws VaultIoException {
        requirePermission("讀取");
        try {
            return read();
        } catch (SecurityException e) {
            throw failure(VaultIoException.Reason.ACCESS_DENIED, "讀取", e);
        } catch (FileNotFoundException e) {
            throw failure(missingOrUnavailable(), "讀取", e);
        } catch (IOException e) {
            throw failure(VaultIoException.Reason.IO, "讀取", e);
        }
    }

    @Override
    public void writeAll(byte[] data) throws VaultIoException {
        requirePermission("寫入");
        // Drive 垃圾桶裡的文件仍開得出寫入串流（T-106 實測：旗標還有可寫），不要把內容寫進垃圾桶
        if (documents.looksTrashed(uri)) {
            throw failure(VaultIoException.Reason.NOT_FOUND, "寫入", null);
        }
        try (OutputStream out = documents.openOverwrite(uri)) {
            out.write(data);
        } catch (SecurityException e) {
            throw failure(VaultIoException.Reason.ACCESS_DENIED, "寫入", e);
        } catch (FileNotFoundException e) {
            throw failure(missingOrUnavailable(), "寫入", e);
        } catch (IOException e) {
            throw failure(VaultIoException.Reason.IO, "寫入", e);
        }
        // 讀回比對：抓到寫到一半、提供者不理會 "wt" 的截斷（舊內容殘留在檔尾）、讀到舊快取
        byte[] back = readBack();
        // Google Drive 實測（T-11）：寫入較短的內容後，緊接著的讀取有時仍是「新內容＋舊檔尾」，約 0.1 秒後才是截斷後的
        // 內容。只有在「前面完全是新內容、只多了檔尾」時稍等再讀；一直不一致仍然算失敗（不理會截斷的提供者照樣抓得到）
        for (int i = 0; i < LATE_TRUNCATE_RETRIES && !Arrays.equals(back, data) && startsWith(back, data); i++) {
            pause();
            back = readBack();
        }
        if (!Arrays.equals(back, data)) {
            // 長度不含內容，記下來分辨「檔尾沒截斷」（讀回較長）、「讀到舊快取」與「寫到一半」
            throw failure(VaultIoException.Reason.IO, "寫入後讀回不符（讀回 " + back.length + "／寫入 " + data.length
                    + " 位元組）", null);
        }
    }

    @Override
    public boolean supportsAtomicReplace() {
        return false;
    }

    private byte[] readBack() throws VaultIoException {
        try {
            return read();
        } catch (SecurityException | IOException e) {
            throw failure(VaultIoException.Reason.IO, "寫入後讀回", e);
        }
    }

    private static boolean startsWith(byte[] back, byte[] data) {
        return back.length > data.length && Arrays.equals(Arrays.copyOf(back, data.length), data);
    }

    private static void pause() throws VaultIoException {
        try {
            Thread.sleep(LATE_TRUNCATE_WAIT_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultIoException(VaultIoException.Reason.IO, "寫入後讀回被中斷");
        }
    }

    private byte[] read() throws IOException, VaultIoException {
        try (InputStream in = documents.openRead(uri)) {
            return VaultLocation.readBounded(in, displayName());
        }
    }

    // 長期權限被撤銷時，部分提供者仍讓這次啟動的暫時權限通過；以長期權限為準，避免重新啟動後才突然讀不到
    private void requirePermission(String action) throws VaultIoException {
        if (!documents.hasPersistedPermission(uri)) {
            throw failure(VaultIoException.Reason.ACCESS_DENIED, action, null);
        }
    }

    // FileNotFoundException 可能是文件真的不在，也可能是提供者拿不到內容（Drive 離線且沒有快取）：
    // 查得到名稱就是後者，除非看得出在 Drive 的垃圾桶裡（T-106：垃圾桶裡的文件名稱也查得到）
    private VaultIoException.Reason missingOrUnavailable() {
        try {
            return documents.queryDisplayName(uri) == null || documents.looksTrashed(uri)
                    ? VaultIoException.Reason.NOT_FOUND : VaultIoException.Reason.IO;
        } catch (SecurityException e) {
            return VaultIoException.Reason.ACCESS_DENIED;
        } catch (IOException | RuntimeException e) {
            return VaultIoException.Reason.IO;
        }
    }

    private VaultIoException failure(VaultIoException.Reason reason, String action, Exception cause) {
        // 只記動作、例外類型與顯示名稱；底層訊息可能含 Uri
        String type = cause == null ? "" : "：" + cause.getClass().getSimpleName();
        return new VaultIoException(reason, action + "失敗" + type + "（" + displayName + "）");
    }
}
