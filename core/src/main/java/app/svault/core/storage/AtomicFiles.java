package app.svault.core.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * 「同資料夾暫存檔 → 讀回比對 → 原子取代」的檔案寫入（Architecture §5 步驟 6、AC-09-3）。
 * PC 的保管庫（{@code FileVaultLocation}）與本機資料夾的 JSON、{@code base/} 都用這個方法寫。
 */
public final class AtomicFiles {

    /** 暫存檔名的後綴：{@code <原檔名>.svault-tmp}，和原檔在同一個資料夾（同一個磁碟區才能原子取代）。 */
    public static final String TEMP_SUFFIX = ".svault-tmp";

    /** 寫入過程的檢查點，只給故障注入測試用。 */
    public enum Stage {
        /** 暫存檔寫了一半（還沒 fsync）。 */
        TEMP_PARTIALLY_WRITTEN,
        /** 暫存檔已寫完並 fsync。 */
        TEMP_WRITTEN,
        /** 暫存檔讀回比對一致，即將取代原檔。 */
        VERIFIED
    }

    /** 檢查點的觀察者；正式程式不傳。 */
    @FunctionalInterface
    public interface Probe {

        /**
         * @param stage 剛到達的檢查點
         * @throws IOException 測試用來模擬在這一步失敗
         */
        void reached(Stage stage) throws IOException;
    }

    private AtomicFiles() {
    }

    /**
     * @param target 要取代的檔案（不存在時新建）
     * @param data   新內容
     * @throws IOException 寫入、讀回比對或取代失敗；這時原檔不變，暫存檔已盡量刪除
     */
    public static void write(Path target, byte[] data) throws IOException {
        write(target, data, stage -> { });
    }

    /**
     * 同 {@link #write(Path, byte[])}，在每個檢查點通知 {@code probe}（故障注入測試）。
     *
     * @param target 要取代的檔案
     * @param data   新內容
     * @param probe  檢查點觀察者
     * @throws IOException 寫入、讀回比對、取代失敗，或 {@code probe} 丟出
     */
    public static void write(Path target, byte[] data, Probe probe) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = tempFileOf(target);
        // 上次中斷留下的暫存檔是不完整的內容，直接丟掉（原檔從來沒被動過）
        Files.deleteIfExists(temp);
        boolean replaced = false;
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                int half = data.length / 2;
                writeFully(channel, ByteBuffer.wrap(data, 0, half));
                probe.reached(Stage.TEMP_PARTIALLY_WRITTEN);
                writeFully(channel, ByteBuffer.wrap(data, half, data.length - half));
                // 取代前先落盤：否則斷電後可能留下「已取代但內容還在快取」的空檔
                channel.force(true);
            }
            probe.reached(Stage.TEMP_WRITTEN);
            // 讀回比對（AC-09-3「確認可完整讀回後才取代原檔」）：比對整個內容，不只長度
            if (!Arrays.equals(data, Files.readAllBytes(temp))) {
                throw new IOException("暫存檔讀回的內容不一致：" + temp.getFileName());
            }
            probe.reached(Stage.VERIFIED);
            // Windows 上 ATOMIC_MOVE 是 MoveFileEx(REPLACE_EXISTING)，同一磁碟區內是原子的
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            replaced = true;
        } finally {
            if (!replaced) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // 刪不掉也沒關係：下一次寫入會先刪掉它，原檔不受影響
                }
            }
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    /**
     * @param target 原檔
     * @return 原檔的暫存檔路徑
     */
    public static Path tempFileOf(Path target) {
        return target.resolveSibling(target.getFileName().toString() + TEMP_SUFFIX);
    }
}
