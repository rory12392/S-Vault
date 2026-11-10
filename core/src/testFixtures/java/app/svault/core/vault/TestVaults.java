package app.svault.core.vault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 測試用的保管庫參數與 KeePassXC 相容測試檔（{@code testFixtures/resources/kdbx/}，只含假資料）。
 *
 * <p>不依賴任何測試框架：core 的 JUnit 6 測試與 android 的儀器測試（JUnit 4）共用。
 */
public final class TestVaults {

    /** 所有測試保管庫的主密碼（假資料，CONVENTIONS §5）。 */
    public static final String PASSWORD = "probe1234";

    /** ① Argon2d 64 MiB／2 次、AES-256、3 筆條目（SETUP S-TD1 ①）。 */
    public static final String KXC_ARGON2D_AES = "kxc-argon2d-aes.kdbx";
    /** ② 同 ①，外層加密為 ChaCha20（S-TD1 ②）。 */
    public static final String KXC_ARGON2D_CHACHA20 = "kxc-argon2d-chacha20.kdbx";
    /** ③ 中文檔名的附件 2 個、自訂欄位（S-TD1 ③）。 */
    public static final String KXC_ATTACHMENTS_FIELDS = "kxc-attachments-fields.kdbx";
    /** ④ 3 層群組、資源回收桶裡 1 筆、1 筆條目有 3 個歷史版本（S-TD1 ④）。 */
    public static final String KXC_GROUPS_RECYCLE_HISTORY = "kxc-groups-recycle-history.kdbx";
    /** ⑤ KDF 為 Argon2id，用來測 AC-01-7（S-TD1 ⑤）。 */
    public static final String KXC_ARGON2ID = "kxc-argon2id.kdbx";
    /** ⑥ 由 ① 以 keepassxc-cli 永久刪除 2 筆條目，含 2 筆 DeletedObjects（T-04 加入）。 */
    public static final String KXC_DELETED_OBJECTS = "kxc-deleted-objects.kdbx";

    /** S-Vault 能開啟的 KeePassXC 測試檔（AC-15-3：至少 5 個）。 */
    public static final List<String> READABLE_KXC_FIXTURES = Collections.unmodifiableList(Arrays.asList(
            KXC_ARGON2D_AES, KXC_ARGON2D_CHACHA20, KXC_ATTACHMENTS_FIELDS, KXC_GROUPS_RECYCLE_HISTORY,
            KXC_DELETED_OBJECTS));

    private TestVaults() {
    }

    /**
     * 單元測試用的低 KDF 參數（Argon2d、1 MiB、1 次、平行度 1；CONVENTIONS §5）。
     * 只有相容性與效能測試使用正式參數。
     *
     * @return 參數
     */
    public static KdfParams fastParams() {
        return new KdfParams(1024L * 1024L, 1, 1);
    }

    /**
     * 建立一個空的保管庫並序列化（{@link #fastParams()}、主密碼 {@link #PASSWORD}）。很多測試的第一步都是
     * 「建立、序列化、關閉」，T-13 從各測試的重複整理到這裡。
     *
     * @param name 保管庫名稱
     * @return 檔案位元組；每次呼叫都是新的保管庫（根群組 UUID 不同）
     */
    public static byte[] newVaultBytes(String name) {
        VaultCodec codec = new VaultCodec(fastParams());
        byte[] password = password();
        try (Vault vault = codec.create(name, password)) {
            return codec.serialize(vault);
        } finally {
            Arrays.fill(password, (byte) 0);
        }
    }

    /**
     * 直接用函式庫設定條目的一個字串欄位（S-Vault 的編輯畫面沒有自訂欄位；T-16 用來做「受保護的自訂欄位」的衝突）。
     * 不改修改時間。
     *
     * @param vault       保管庫
     * @param entryId     條目
     * @param name        欄位名稱
     * @param value       值
     * @param isProtected 以 {@code Protected="True"} 儲存（KeePassXC 的「保護」）
     */
    public static void setField(Vault vault, UUID entryId, String name, String value, boolean isProtected) {
        vault.setField(entryId, name, value, isProtected);
    }

    /**
     * 保管庫「使用者看得到的內容」：群組的位置與名稱、條目的位置與每個比較欄位，不含時間、歷史與刪除紀錄。還原時
     * 會加上歷史與刪除紀錄（{@link RestoreMarks}，T-27），測試用它確認內容仍和備份相同。
     *
     * @param vault 保管庫
     * @return 可以直接 {@code equals} 比較的內容
     */
    public static Object content(Vault vault) {
        VaultSnapshot s = vault.snapshot();
        Map<UUID, List<Object>> out = new TreeMap<>();
        for (GroupSnapshot g : s.groups().values()) {
            out.put(g.uuid(), Arrays.asList(g.parentId(), g.fields()));
        }
        for (EntrySnapshot e : s.entries().values()) {
            out.put(e.uuid(), Arrays.asList(e.groupId(), e.fields()));
        }
        return out;
    }

    /**
     * @return 主密碼的 UTF-8 位元組；每次都是新的陣列，呼叫端可以清零
     */
    public static byte[] password() {
        return PASSWORD.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 讀取相容測試檔。
     *
     * @param name 檔名，例如 {@link #KXC_ARGON2D_AES}
     * @return 檔案位元組
     * @throws IllegalArgumentException 檔案不存在時
     */
    public static byte[] fixture(String name) {
        try (InputStream in = TestVaults.class.getResourceAsStream("/kdbx/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("找不到測試檔：" + name);
            }
            // InputStream.readAllBytes 在 Android API 33 才有；儀器測試也用這個類別，所以手動複製
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
