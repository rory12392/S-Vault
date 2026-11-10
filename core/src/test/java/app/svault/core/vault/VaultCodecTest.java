package app.svault.core.vault;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.linguafranca.pwdb.kdbx.KdbxHeader;
import org.linguafranca.pwdb.kdbx.KdbxSerializer;

/**
 * {@link VaultCodec} 的建立、開啟、序列化與錯誤分類（AC-01-1、AC-01-3、AC-01-7、AC-01-8）。
 */
class VaultCodecTest {

    private final VaultCodec fast = new VaultCodec(TestVaults.fastParams());

    @Test
    @DisplayName("AC-01-8：建立的保管庫是 KDBX 4、Argon2d 64 MiB／2 次／平行度 4、AES-256、內層 ChaCha20")
    void createUsesProductionParameters() throws Exception {
        VaultCodec codec = new VaultCodec();
        byte[] bytes;
        try (Vault vault = codec.create("測試保管庫", TestVaults.password())) {
            bytes = codec.serialize(vault);
        }
        VaultFormat expected = new VaultFormat(4, "AES-256", "Argon2d", 64L * 1024 * 1024, 2, 4, "ChaCha20");
        try (Vault reopened = codec.open(bytes, TestVaults.password())) {
            assertEquals(expected, reopened.format());
        }
        // 不只相信自己的模型：用函式庫直接解析外層標頭再確認一次
        KdbxHeader header = KdbxSerializer.readOuterHeader(new ByteArrayInputStream(bytes), new KdbxHeader());
        assertEquals(4, header.getVersion());
        assertEquals("Argon2", header.getKeyDerivationFunction().getName());
        assertEquals(64L * 1024 * 1024, header.getKdfParameters().mustGet("M").asLong());
        assertEquals(2L, header.getKdfParameters().mustGet("I").asLong());
        assertEquals(4, header.getKdfParameters().mustGet("P").asInteger());
        assertEquals("AES", header.getCipherAlgorithm().getName());
    }

    @Test
    @DisplayName("AC-01-1：建立、加入條目、存檔後以同一主密碼開啟，看到相同的條目")
    void createSerializeOpenRoundTrip() throws Exception {
        byte[] bytes;
        try (Vault vault = fast.create("我的密碼", TestVaults.password())) {
            TestEntries.add(vault, "GitHub", "rory", "s3cret-密碼", "https://github.com", "第一行\n第二行");
            TestEntries.add(vault, "銀行", "user01", "p@ss", "https://bank.example", "");
            bytes = fast.serialize(vault);
        }
        try (Vault reopened = fast.open(bytes, TestVaults.password())) {
            assertEquals("我的密碼", reopened.name());
            List<String> titles = reopened.entries().stream().map(EntryView::title).sorted()
                    .collect(Collectors.toList());
            assertEquals(Arrays.asList("GitHub", "銀行"), titles);
            EntryView github = reopened.entries().stream().filter(e -> e.title().equals("GitHub")).findFirst()
                    .orElseThrow(AssertionError::new);
            assertEquals("rory", github.username());
            assertEquals("s3cret-密碼", github.password());
            assertEquals("https://github.com", github.url());
            assertEquals("第一行\n第二行", github.notes());
            assertEquals(reopened.id(), github.groupId());
        }
    }

    @Test
    @DisplayName("AC-01-1：建立的保管庫描述是空的，不帶函式庫範本的「New Database created by KeePassJava2」")
    void createLeavesDescriptionEmpty() throws Exception {
        byte[] bytes;
        try (Vault vault = fast.create("我的密碼", TestVaults.password())) {
            bytes = fast.serialize(vault);
        }
        // 讀回檔案再看：KeePassXC 的「資料庫設定 → 描述」顯示的就是這個欄位
        try (Vault reopened = fast.open(bytes, TestVaults.password())) {
            String description = reopened.database().getDescription();
            assertTrue(description == null || description.isEmpty(), "描述應該是空的：" + description);
        }
    }

    @Test
    @DisplayName("AC-01-3：主密碼錯誤時拋 WrongPasswordException，輸入的位元組不變")
    void wrongPassword() {
        byte[] bytes = TestVaults.fixture(TestVaults.KXC_ARGON2D_AES);
        byte[] before = bytes.clone();
        byte[] wrong = "not-the-password".getBytes(StandardCharsets.UTF_8);
        assertThrows(WrongPasswordException.class, () -> new VaultCodec().open(bytes, wrong));
        assertArrayEquals(before, bytes);
    }

    @Test
    @DisplayName("AC-01-3：S-Vault 自己建立的檔案，主密碼錯誤時也是 WrongPasswordException")
    void wrongPasswordOnOwnFile() throws Exception {
        byte[] bytes;
        try (Vault vault = fast.create("v", TestVaults.password())) {
            bytes = fast.serialize(vault);
        }
        assertThrows(WrongPasswordException.class,
                () -> fast.open(bytes, "probe12345".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("AC-01-7：Argon2id 的檔案拋 UnsupportedKdfException（Argon2id），不需要主密碼也不改動輸入")
    void argon2idIsUnsupported() {
        byte[] bytes = TestVaults.fixture(TestVaults.KXC_ARGON2ID);
        byte[] before = bytes.clone();
        UnsupportedKdfException e = assertThrows(UnsupportedKdfException.class,
                () -> fast.open(bytes, TestVaults.password()));
        assertEquals("Argon2id", e.setting());
        UnsupportedKdfException wrongPassword = assertThrows(UnsupportedKdfException.class,
                () -> fast.open(bytes, "x".getBytes(StandardCharsets.UTF_8)));
        assertEquals("Argon2id", wrongPassword.setting());
        assertArrayEquals(before, bytes);
    }

    @Test
    @DisplayName("不是 KDBX、被截斷、資料區塊被改動的檔案都拋 CorruptVaultException")
    void corruptFiles() throws Exception {
        assertThrows(CorruptVaultException.class,
                () -> fast.open("hello".getBytes(StandardCharsets.UTF_8), TestVaults.password()));
        assertThrows(CorruptVaultException.class, () -> fast.open(new byte[0], TestVaults.password()));
        byte[] good;
        try (Vault vault = fast.create("v", TestVaults.password())) {
            TestEntries.add(vault, "a", "b", "c", "d", "e");
            good = fast.serialize(vault);
        }
        byte[] truncated = Arrays.copyOf(good, good.length / 2);
        assertThrows(CorruptVaultException.class, () -> fast.open(truncated, TestVaults.password()));
        byte[] flipped = good.clone();
        flipped[flipped.length - 40] ^= 0x01;
        assertThrows(CorruptVaultException.class, () -> fast.open(flipped, TestVaults.password()));
    }

    @Test
    @DisplayName("每次序列化都換新的主種子、IV 與內層串流金鑰（函式庫預設會沿用讀入時的值）")
    void freshSeedsOnEverySerialize() throws Exception {
        try (Vault vault = fast.open(TestVaults.fixture(TestVaults.KXC_ARGON2D_CHACHA20), TestVaults.password())) {
            KdbxHeader original = header(TestVaults.fixture(TestVaults.KXC_ARGON2D_CHACHA20));
            KdbxHeader first = header(fast.serialize(vault));
            KdbxHeader second = header(fast.serialize(vault));
            assertFalse(Arrays.equals(original.getMasterSeed(), first.getMasterSeed()));
            assertFalse(Arrays.equals(first.getMasterSeed(), second.getMasterSeed()));
            assertFalse(Arrays.equals(original.getEncryptionIv(), first.getEncryptionIv()));
            assertFalse(Arrays.equals(first.getEncryptionIv(), second.getEncryptionIv()));
            // ChaCha20 的 nonce 是 12 位元組
            assertEquals(12, first.getEncryptionIv().length);
        }
    }

    @Test
    @DisplayName("開檔只做 1 次 KDF；之後的序列化與讀回驗證重用已衍生的金鑰（0 次）")
    void derivedKeyIsReused() throws Exception {
        VaultCodec codec = new VaultCodec();
        try (Vault vault = codec.open(TestVaults.fixture(TestVaults.KXC_ARGON2D_AES), TestVaults.password())) {
            assertEquals(1, vault.keyDerivations());
            byte[] saved = codec.serialize(vault);
            assertEquals(1, vault.keyDerivations());
            try (Vault readBack = codec.openWithKeyOf(saved, vault)) {
                assertEquals(0, readBack.keyDerivations());
                assertEquals(vault.entries().size(), readBack.entries().size());
            }
        }
    }

    @Test
    @DisplayName("createWithKeyOf（AC-08-5 的衝突暫存）：同一把金鑰與 KDF 參數的新保管庫，0 次 KDF，主密碼打得開")
    void createWithKeyOfReusesKey() throws Exception {
        VaultCodec codec = new VaultCodec();
        try (Vault vault = codec.open(TestVaults.fixture(TestVaults.KXC_ARGON2D_AES), TestVaults.password())) {
            byte[] bytes;
            try (Vault side = codec.createWithKeyOf("暫存", vault)) {
                assertEquals(0, side.entries().size());
                assertFalse(side.id().equals(vault.id()));
                bytes = codec.serialize(side);
                assertEquals(0, side.keyDerivations());
            }
            assertFalse(vault.isClosed(), "金鑰是複本：暫存上鎖不影響開啟中的保管庫");
            try (Vault readBack = codec.openWithKeyOf(bytes, vault)) {
                assertEquals(0, readBack.keyDerivations());
            }
            try (Vault byPassword = fast.open(bytes, TestVaults.password())) {
                assertEquals("暫存", byPassword.name());
            }
        }
    }

    @Test
    @DisplayName("openWithKeyOf：另一個主密碼的檔案拋 WrongPasswordException")
    void openWithKeyOfOtherPassword() throws Exception {
        byte[] other;
        try (Vault v = fast.create("other", "different".getBytes(StandardCharsets.UTF_8))) {
            other = fast.serialize(v);
        }
        try (Vault mine = fast.create("mine", TestVaults.password())) {
            assertThrows(WrongPasswordException.class, () -> fast.openWithKeyOf(other, mine));
        }
    }

    @Test
    @DisplayName("上鎖（close）後不能再序列化，也不能當作金鑰來源")
    void closedVaultIsUnusable() throws Exception {
        Vault vault = fast.create("v", TestVaults.password());
        byte[] bytes = fast.serialize(vault);
        vault.close();
        assertTrue(vault.isClosed());
        assertThrows(IllegalStateException.class, () -> fast.serialize(vault));
        assertThrows(IllegalStateException.class, () -> fast.openWithKeyOf(bytes, vault));
        assertThrows(IllegalStateException.class, vault::entries);
    }

    @Test
    @DisplayName("建立時不保留呼叫端的主密碼陣列：呼叫端清零後仍能存檔與開啟")
    void passwordArrayIsNotRetained() throws Exception {
        byte[] password = TestVaults.password();
        byte[] bytes;
        try (Vault vault = fast.create("v", password)) {
            Arrays.fill(password, (byte) 0);
            bytes = fast.serialize(vault);
        }
        try (Vault reopened = fast.open(bytes, TestVaults.password())) {
            assertEquals("v", reopened.name());
        }
    }

    @Test
    @DisplayName("EntryView／GroupView 的 toString 不含任何條目內容")
    void viewsDoNotLeakContent() throws Exception {
        try (Vault vault = fast.create("秘密名稱", TestVaults.password())) {
            TestEntries.add(vault, "標題X", "帳號Y", "密碼Z", "https://u.example", "備註W");
            String entry = vault.entries().get(0).toString();
            for (String secret : Arrays.asList("標題X", "帳號Y", "密碼Z", "u.example", "備註W")) {
                assertFalse(entry.contains(secret), entry);
            }
            assertFalse(vault.groups().get(0).toString().contains("秘密名稱"));
        }
    }

    @Test
    @DisplayName("資安總檢 L-1：KDF 參數被改大（記憶體、迭代、超大的 UInt64）時不跑 KDF，直接拋 UnsupportedKdfException")
    void tooCostlyKdfIsRejectedBeforeDerivation() throws Exception {
        byte[] ok = TestVaults.fixture(TestVaults.KXC_ARGON2D_AES);
        long[][] cases = {
            {'M', KdbxHeaderScan.MAX_ARGON2_MEMORY + 1},
            {'I', KdbxHeaderScan.MAX_ARGON2_ITERATIONS + 1},
            {'M', -1L},                           // UInt64 的最大值
        };
        for (long[] c : cases) {
            byte[] bytes = withKdfParam(ok, (char) c[0], c[1]);
            UnsupportedKdfException e = assertThrows(UnsupportedKdfException.class,
                    () -> fast.open(bytes, TestVaults.password()), () -> (char) c[0] + "=" + c[1]);
            assertEquals(UnsupportedKdfException.TOO_COSTLY, e.setting());
        }
        // 剛好在上限內的不受影響：KeePassXC 建立的檔案照常開啟
        try (Vault vault = fast.open(ok, TestVaults.password())) {
            assertTrue(vault.format().memoryBytes() <= KdbxHeaderScan.MAX_ARGON2_MEMORY);
        }
    }

    @Test
    @DisplayName("C-4：成本超過預設的 10 倍時先丟 CostlyKdfException（不跑 KDF）；確認後才照常開，10 倍以內不問")
    void costlyKdfNeedsConfirmation() throws Exception {
        byte[] ok = TestVaults.fixture(TestVaults.KXC_ARGON2D_AES);
        byte[] costly = withKdfParam(ok, 'I', 30);       // 64 MiB × 30 次＝預設的 15 倍

        CostlyKdfException e = assertThrows(CostlyKdfException.class, () -> fast.open(costly, TestVaults.password()));
        assertEquals(15.0, e.factor(), 0.01);
        assertEquals(15.0 * 2.0, KdfCost.estimatedSeconds(e.factor(), 2.0), 0.01);

        // 確認後不再擋（標頭被改過，HMAC 會對不上，所以之後是別的錯誤，不是 CostlyKdfException）
        byte[] slightly = withKdfParam(ok, 'I', 21);     // 10.5 倍
        assertThrows(CostlyKdfException.class, () -> fast.open(slightly, TestVaults.password()));
        VaultException after = assertThrows(VaultException.class, () -> fast.open(slightly, TestVaults.password(), true));
        assertFalse(after instanceof CostlyKdfException);

        byte[] tenTimes = withKdfParam(ok, 'I', 20);     // 剛好 10 倍：不問
        VaultException plain = assertThrows(VaultException.class, () -> fast.open(tenTimes, TestVaults.password()));
        assertFalse(plain instanceof CostlyKdfException);
    }

    @Test
    @DisplayName("C-4：背景同步用已開啟保管庫的金鑰開遠端時，比目前這份更貴的也先擋（雲端標頭被改大）")
    void costlierRemoteIsStoppedDuringSync() throws Exception {
        byte[] ok = TestVaults.fixture(TestVaults.KXC_ARGON2D_AES);
        try (Vault open = fast.open(ok, TestVaults.password())) {
            byte[] costly = withKdfParam(ok, 'I', 30);
            assertThrows(CostlyKdfException.class, () -> fast.openWithKeyOf(costly, open));
            try (Vault again = fast.openWithKeyOf(ok, open)) {
                assertEquals(1.0, KdfCost.factorOf(again.format()), 0.01);
            }
        }
    }

    /** 把外層標頭 KDF 參數裡的 UInt64 項目 {@code key} 改成 {@code value}（VariantDictionary：型別 0x05、鍵長、鍵、值長 8）。 */
    private static byte[] withKdfParam(byte[] bytes, char key, long value) {
        byte[] out = bytes.clone();
        byte[] marker = {0x05, 1, 0, 0, 0, (byte) key, 8, 0, 0, 0};
        for (int i = 0; i + marker.length + 8 <= out.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(out, i, i + marker.length), marker)) {
                java.nio.ByteBuffer.wrap(out, i + marker.length, 8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value);
                return out;
            }
        }
        throw new AssertionError("找不到 KDF 參數 " + key);
    }

    private static KdbxHeader header(byte[] bytes) throws Exception {
        return KdbxSerializer.readOuterHeader(new ByteArrayInputStream(bytes), new KdbxHeader());
    }
}
