package app.svault.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import app.svault.core.vault.TestVaults;
import app.svault.core.vault.UnsupportedKdfException;
import app.svault.core.vault.Vault;
import app.svault.core.vault.VaultCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 手機上的保管庫檢查（M-01 整合的驗證清單 ⑥⑦）：儀器測試讀得到 core testFixtures 的 {@code kdbx/} 資源；
 * 以正式參數（AC-01-8，64 MiB／2 次）建立的保管庫在手機上開檔只算一次 KDF，存檔與讀回驗證不再重算
 * （效能量測）。計時印在 logcat（tag {@code SVaultPerf}），只有假資料。
 */
@RunWith(AndroidJUnit4.class)
public class VaultOnDeviceTest {

    private static final String TAG = "SVaultPerf";

    @Test
    public void testFixturesAreReadableOnDevice() throws Exception {
        VaultCodec codec = new VaultCodec();
        for (String name : TestVaults.READABLE_KXC_FIXTURES) {
            byte[] password = TestVaults.password();
            try (Vault vault = codec.open(TestVaults.fixture(name), password)) {
                assertFalse(name + " 沒有條目", vault.entries().isEmpty());
            }
        }
        try {
            codec.open(TestVaults.fixture(TestVaults.KXC_ARGON2ID), TestVaults.password()).close();
            fail("Argon2id 的檔案應該被擋下（AC-01-7）");
        } catch (UnsupportedKdfException expected) {
            // AC-01-7：不支援的 KDF 要明確回報
        }
    }

    @Test
    public void openComputesKdfOnceAndSaveDoesNotRecompute() throws Exception {
        VaultCodec codec = new VaultCodec();
        byte[] data;
        try (Vault created = codec.create("裝置檢查", TestVaults.password())) {
            data = codec.serialize(created);
        }
        List<Long> openMs = new ArrayList<>();
        List<Long> saveMs = new ArrayList<>();
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            try (Vault vault = codec.open(data, TestVaults.password())) {
                openMs.add((System.nanoTime() - start) / 1_000_000);
                // 存檔＋讀回驗證（SaveCoordinator 的步驟 4–5）：沿用已開啟保管庫的金鑰，不重算 KDF
                start = System.nanoTime();
                byte[] saved = codec.serialize(vault);
                try (Vault readBack = codec.openWithKeyOf(saved, vault)) {
                    assertEquals(vault.id(), readBack.id());
                }
                saveMs.add((System.nanoTime() - start) / 1_000_000);
            }
        }
        long open = median(openMs);
        long save = median(saveMs);
        Log.i(TAG, "VaultOnDeviceTest：開檔 " + openMs + " ms（中位數 " + open + "）；存檔＋讀回 " + saveMs
                + " ms（中位數 " + save + "）");
        assertTrue("開檔要在 5 秒內（防呆上限；效能需求的 2 秒看 logcat 的中位數）：" + openMs, open < 5_000);
        assertTrue("存檔＋讀回不應再算 KDF（應遠小於開檔）：開檔 " + openMs + "，存檔 " + saveMs, save * 4 < open);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }
}
