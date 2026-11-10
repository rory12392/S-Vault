package app.svault.desktop.platform;

import app.svault.core.storage.AtomicFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * {@link FileVaultLocationTest} 的子行程：寫入保管庫，暫存檔寫到一半時建立標記檔並停住，等父行程強制結束它
 * （AC-09-3「在寫入途中強制中斷程序」）。
 */
public final class InterruptedWriter {

    private InterruptedWriter() {
    }

    /**
     * @param args 0：保管庫檔案；1：要寫入的內容檔；2：寫到一半時建立的標記檔
     * @throws Exception 任何失敗（父行程會看到非 0 的結束碼）
     */
    public static void main(String[] args) throws Exception {
        Path target = Paths.get(args[0]);
        byte[] data = Files.readAllBytes(Paths.get(args[1]));
        Path marker = Paths.get(args[2]);
        FileVaultLocation location = new FileVaultLocation(target, stage -> {
            if (stage == AtomicFiles.Stage.TEMP_PARTIALLY_WRITTEN) {
                Files.write(marker, new byte[0]);
                // 停在這裡直到被強制結束；不會自己醒來
                try {
                    Thread.sleep(Long.MAX_VALUE);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        location.writeAll(data);
        // 走到這裡表示沒有被中斷，用特別的結束碼讓父行程知道測試沒有測到
        System.exit(3);
    }
}
