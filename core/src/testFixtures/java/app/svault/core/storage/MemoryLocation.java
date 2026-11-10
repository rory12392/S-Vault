package app.svault.core.storage;

import java.util.Arrays;

/**
 * 測試用的保管庫位置：內容放在記憶體，可以指定讀或寫要失敗（故障注入）。
 *
 * <p>core、desktop、android（含儀器測試）共用（T-13 從 core 的測試與 android 的 {@code TestVaultFiles.TestLocation}
 * 整理到 testFixtures）。儀器測試會在測試執行緒切換離線、在 App 的背景執行緒讀寫，所以狀態欄位是 {@code volatile}。
 */
public final class MemoryLocation implements VaultLocation {

    private final String id;
    private final boolean atomicReplace;
    private volatile byte[] content;
    private volatile boolean failRead;
    private volatile boolean failWrite;
    private volatile int writes;
    private volatile byte[] afterNextRead;
    private volatile byte[][] flip;

    /**
     * 支援原子取代的位置（像 PC 的檔案）。
     *
     * @param id      位置字串
     * @param content 初始內容；{@code null} 表示檔案不存在
     */
    public MemoryLocation(String id, byte[] content) {
        this(id, content, true);
    }

    /**
     * @param id            位置字串
     * @param content       初始內容；{@code null} 表示檔案不存在
     * @param atomicReplace {@link #supportsAtomicReplace()} 的回傳值（{@code false} 像 Android 的 SAF 文件）
     */
    public MemoryLocation(String id, byte[] content, boolean atomicReplace) {
        this.id = id;
        this.content = content == null ? null : content.clone();
        this.atomicReplace = atomicReplace;
    }

    /** @param fail 之後的讀取是否失敗 */
    public void failRead(boolean fail) {
        this.failRead = fail;
    }

    /** @param fail 之後的寫入是否失敗（內容不變，像原子取代失敗） */
    public void failWrite(boolean fail) {
        this.failWrite = fail;
    }

    /** @param offline 之後的讀寫是否都失敗（模擬離線） */
    public void offline(boolean offline) {
        failRead(offline);
        failWrite(offline);
    }

    /** @return 目前內容的複本；不存在時為 {@code null} */
    public byte[] content() {
        byte[] current = content;
        return current == null ? null : current.clone();
    }

    /** @param data 直接換掉內容（模擬別的裝置寫入） */
    public void replace(byte[] data) {
        content = data.clone();
    }

    /**
     * 下一次讀取回傳目前內容之後，馬上換成 {@code data}：模擬「這台讀完遠端、還沒寫回之前，別的裝置寫入了」（C-2）。
     *
     * @param data 讀完之後的新內容
     */
    public void replaceAfterNextRead(byte[] data) {
        afterNextRead = data.clone();
    }

    /**
     * 之後每次讀取完都換成另一個版本（{@code a}、{@code b} 輪流）：模擬遠端一直被別人寫入，重試幾次都撞到（C-2，Codex M-04
     * 審查待辦 4）。傳 {@code null} 停止。
     *
     * @param a 版本一
     * @param b 版本二（要和 {@code a} 不同）
     */
    public void changeAfterEveryRead(byte[] a, byte[] b) {
        flip = a == null ? null : new byte[][] {a.clone(), b.clone()};
    }

    /** @return 成功寫入的次數 */
    public int writes() {
        return writes;
    }

    @Override
    public String displayName() {
        return id;
    }

    @Override
    public String persistentId() {
        return id;
    }

    @Override
    public boolean exists() {
        return content != null;
    }

    @Override
    public byte[] readAll() throws VaultIoException {
        if (failRead) {
            throw new VaultIoException(VaultIoException.Reason.IO, "模擬讀取失敗");
        }
        byte[] current = content;
        if (current == null) {
            throw new VaultIoException(VaultIoException.Reason.NOT_FOUND, "檔案不存在：" + id);
        }
        byte[] next = afterNextRead;
        if (next != null) {
            afterNextRead = null;
            content = next;
        }
        byte[][] f = flip;
        if (f != null) {
            content = java.util.Arrays.equals(current, f[0]) ? f[1] : f[0];
        }
        return current.clone();
    }

    @Override
    public synchronized void writeAll(byte[] data) throws VaultIoException {
        if (failWrite) {
            throw new VaultIoException(VaultIoException.Reason.IO, "模擬寫入失敗");
        }
        content = Arrays.copyOf(data, data.length);
        writes++;
    }

    @Override
    public boolean supportsAtomicReplace() {
        return atomicReplace;
    }
}
