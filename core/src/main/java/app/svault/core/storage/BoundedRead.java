package app.svault.core.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 有大小上限的整檔讀取（{@link VaultLocation#readBounded}；上限可調是為了測試不必真的配置 256 MiB）。
 */
final class BoundedRead {

    private BoundedRead() {
    }

    /**
     * @param in          檔案內容（不關閉）
     * @param displayName 給錯誤訊息用的檔名
     * @param max         位元組上限
     * @return 全部位元組
     * @throws IOException      讀取失敗
     * @throws VaultIoException 超過 {@code max}（{@link VaultIoException.Reason#TOO_LARGE}）
     */
    static byte[] read(InputStream in, String displayName, long max) throws IOException, VaultIoException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > max) {
                throw new VaultIoException(VaultIoException.Reason.TOO_LARGE, "檔案過大（" + displayName + "）");
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    /**
     * 讀本機檔，超過上限就停（Codex 待確認：本機的 base／pending／備份／設定檔原本整檔讀進記憶體，沒有上限）。
     *
     * @param file 檔案
     * @param max  位元組上限
     * @return 全部位元組
     * @throws java.nio.file.NoSuchFileException 檔案不存在
     * @throws IOException      讀取失敗
     * @throws VaultIoException 超過 {@code max}（{@link VaultIoException.Reason#TOO_LARGE}）
     */
    static byte[] file(Path file, long max) throws IOException, VaultIoException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in, String.valueOf(file.getFileName()), max);
        }
    }
}
