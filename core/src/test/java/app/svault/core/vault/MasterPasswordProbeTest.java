package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 確認輸入的是不是開啟中保管庫的主密碼（T-35，支撐 AC-11-1 的啟用）：比對複合金鑰，不重算 KDF、不保留主密碼。只用假資料。
 */
class MasterPasswordProbeTest {

    private final VaultCodec codec = new VaultCodec(TestVaults.fastParams());

    @Test
    @DisplayName("T-35：主密碼正確時相符、錯誤時不符；都不執行 KDF")
    void matchesOnlyTheRightPassword() throws Exception {
        try (Vault vault = codec.open(TestVaults.newVaultBytes("家用"), TestVaults.password())) {
            int before = vault.keyDerivations();
            try (MasterPasswordProbe right = MasterPasswordProbe.of(TestVaults.password());
                 MasterPasswordProbe wrong = MasterPasswordProbe.of(bytes("probe12345"));
                 MasterPasswordProbe empty = MasterPasswordProbe.of(new byte[0])) {
                assertTrue(right.matches(vault));
                assertFalse(wrong.matches(vault));
                assertFalse(empty.matches(vault));
            }
            assertEquals(before, vault.keyDerivations());
        }
    }

    @Test
    @DisplayName("T-35：不保留傳入的陣列——建立後清零照樣比對得到")
    void doesNotKeepThePassword() throws Exception {
        try (Vault vault = codec.open(TestVaults.newVaultBytes("家用"), TestVaults.password())) {
            byte[] password = TestVaults.password();
            try (MasterPasswordProbe probe = MasterPasswordProbe.of(password)) {
                Arrays.fill(password, (byte) 0);
                assertTrue(probe.matches(vault));
            }
        }
    }

    @Test
    @DisplayName("T-35：用同一把金鑰開的複本（讀回驗證、合併的路徑）也比對得到")
    void matchesVaultOpenedWithSameKey() throws Exception {
        try (Vault vault = codec.open(TestVaults.newVaultBytes("家用"), TestVaults.password());
             Vault copy = codec.openWithKeyOf(codec.serialize(vault), vault);
             MasterPasswordProbe probe = MasterPasswordProbe.of(TestVaults.password())) {
            assertTrue(probe.matches(copy));
        }
    }

    @Test
    @DisplayName("T-35：保管庫已上鎖、或探針已關閉時丟 IllegalStateException")
    void lockedOrClosedThrows() throws Exception {
        Vault vault = codec.open(TestVaults.newVaultBytes("家用"), TestVaults.password());
        MasterPasswordProbe probe = MasterPasswordProbe.of(TestVaults.password());
        vault.close();
        assertThrows(IllegalStateException.class, () -> probe.matches(vault));
        try (Vault open = codec.open(TestVaults.newVaultBytes("家用"), TestVaults.password())) {
            probe.close();
            probe.close();
            assertThrows(IllegalStateException.class, () -> probe.matches(open));
        }
    }

    @Test
    @DisplayName("T-35：toString 不含任何金鑰內容；null 丟 NullPointerException")
    void toStringAndNull() {
        try (MasterPasswordProbe probe = MasterPasswordProbe.of(TestVaults.password())) {
            assertEquals("MasterPasswordProbe[***]", probe.toString());
        }
        assertThrows(NullPointerException.class, () -> MasterPasswordProbe.of(null));
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
