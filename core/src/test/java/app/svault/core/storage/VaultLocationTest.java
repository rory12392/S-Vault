package app.svault.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link VaultLocation#readBounded} 與 {@link BoundedRead}：平台讀保管庫時共用的大小上限（2026-11-03 資安總檢 L-1）。
 */
class VaultLocationTest {

    @Test
    @DisplayName("資安總檢 L-1：一般大小的檔案完整讀回")
    void readsWholeStream() throws Exception {
        byte[] data = new byte[200_000];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        assertArrayEquals(data, VaultLocation.readBounded(new ByteArrayInputStream(data), "v.kdbx"));
    }

    @Test
    @DisplayName("資安總檢 L-1：剛好等於上限的可以讀，多一個位元組就丟 TOO_LARGE")
    void stopsAboveLimit() throws Exception {
        byte[] data = new byte[100_000];
        assertEquals(data.length, BoundedRead.read(new ByteArrayInputStream(data), "v.kdbx", data.length).length);
        VaultIoException e = assertThrows(VaultIoException.class,
                () -> BoundedRead.read(new ByteArrayInputStream(data), "v.kdbx", data.length - 1));
        assertEquals(VaultIoException.Reason.TOO_LARGE, e.reason());
        assertEquals(256L * 1024 * 1024, VaultLocation.MAX_BYTES);
    }
}
