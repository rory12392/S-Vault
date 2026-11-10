package app.svault.android.feature.biometric;

import android.content.Context;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;

/**
 * 用 Keystore 金鑰加密、解密主密碼（US-11：AC-11-2、AC-11-3、AC-11-4 的金鑰與加解密部分，T-70）。
 *
 * <p>兩段式流程，中間由呼叫端用 {@code BiometricPrompt} 搭配 {@code CryptoObject} 驗證回傳的加密器
 * （{@link BioPrompt}）：
 * <ul>
 *   <li>啟用：{@link #prepareEnable} → 生物辨識 → {@link #completeEnable}（密文與 IV 存私有偏好）</li>
 *   <li>解鎖：{@link #prepareUnlock} → 生物辨識 → {@link #completeUnlock}（取得主密碼交給核心）</li>
 * </ul>
 * 金鑰失效、密文損壞時自動刪除金鑰與密文，並以 {@link BioException} 回報原因，呼叫端退回主密碼輸入。
 *
 * <p>主密碼明文只出現在 {@link #completeEnable} 的參數與 {@link #completeUnlock} 的回傳值，本類別不保留、
 * 不寫日誌；清零由持有陣列的呼叫端負責（規格 §1）。保管庫 ID 當成 GCM 的附加資料，密文搬到別的保管庫解不開。
 */
public final class BioVault {

    /** Keystore 別名前綴（Architecture §2：{@code svault_bio_<保管庫ID>}）。 */
    static final String ALIAS_PREFIX = "svault_bio_";

    private final BioKeys keys;
    private final BioSecretStore store;

    BioVault(BioKeys keys, BioSecretStore store) {
        this.keys = keys;
        this.store = store;
    }

    /**
     * @param context 任何 Context
     * @return 使用 Android Keystore 與 App 私有偏好的實例
     */
    public static BioVault of(Context context) {
        return new BioVault(new KeystoreBioKeys(), new PrefsBioSecretStore(context));
    }

    /**
     * @param vaultId 保管庫 ID（根群組 UUID）
     * @return 這個保管庫已啟用生物辨識解鎖（有存密文）時為 {@code true}；不代表金鑰仍有效
     */
    public boolean isEnabled(String vaultId) {
        try {
            return store.get(normalize(vaultId)) != null;
        } catch (BioException e) {
            return false;
        }
    }

    /**
     * 啟用的第一步：產生新金鑰（取代舊的）並回傳等待生物辨識的加密器。
     *
     * @param vaultId 保管庫 ID
     * @return 加密模式的加密器，交給 {@code BiometricPrompt.CryptoObject}
     * @throws BioException 沒有登錄強生物特徵、金鑰庫錯誤
     */
    public Cipher prepareEnable(String vaultId) throws BioException {
        String id = normalize(vaultId);
        // 舊密文用的是即將被取代的金鑰，先移除，免得留下解不開的資料
        store.remove(id);
        return keys.newEncryptCipher(alias(id));
    }

    /**
     * 啟用的第二步：用通過生物辨識的加密器加密主密碼並存起來。失敗時刪除金鑰，不留下半套狀態。
     *
     * @param vaultId        保管庫 ID
     * @param authenticated  {@link #prepareEnable} 回傳、已通過生物辨識的加密器
     * @param masterPassword 主密碼的 UTF-8 位元組（本方法不清零、不保留；呼叫端用完清零）
     * @throws BioException 加密或寫入失敗
     */
    public void completeEnable(String vaultId, Cipher authenticated, byte[] masterPassword) throws BioException {
        String id = normalize(vaultId);
        try {
            authenticated.updateAAD(aad(id));
            byte[] ciphertext = authenticated.doFinal(masterPassword);
            store.put(id, new BioSecretStore.Sealed(authenticated.getIV(), ciphertext));
        } catch (GeneralSecurityException | RuntimeException e) {
            disableQuietly(id);
            throw new BioException(BioReason.FAILED, e);
        }
    }

    /**
     * 解鎖的第一步：讀出 IV 並回傳等待生物辨識的解密器。
     *
     * @param vaultId 保管庫 ID
     * @return 解密模式的加密器，交給 {@code BiometricPrompt.CryptoObject}
     * @throws BioException 沒有啟用（{@link BioReason#NOT_ENABLED}）；金鑰已失效（{@link BioReason#KEY_INVALIDATED}，
     *                      已刪除金鑰與密文，AC-11-3）；密文損壞（{@link BioReason#CORRUPT_DATA}，已刪除）
     */
    public Cipher prepareUnlock(String vaultId) throws BioException {
        String id = normalize(vaultId);
        BioSecretStore.Sealed sealed;
        try {
            sealed = store.get(id);
        } catch (BioException e) {
            disableQuietly(id);
            throw e;
        }
        if (sealed == null) {
            throw new BioException(BioReason.NOT_ENABLED);
        }
        try {
            return keys.decryptCipher(alias(id), sealed.iv());
        } catch (BioException e) {
            if (e.reason() == BioReason.KEY_INVALIDATED) {
                disableQuietly(id);
            }
            throw e;
        }
    }

    /**
     * 解鎖的第二步：用通過生物辨識的解密器取回主密碼。
     *
     * @param vaultId       保管庫 ID
     * @param authenticated {@link #prepareUnlock} 回傳、已通過生物辨識的解密器
     * @return 主密碼的 UTF-8 位元組；呼叫端交給核心解鎖後清零
     * @throws BioException 密文驗證失敗（{@link BioReason#CORRUPT_DATA}，已刪除金鑰與密文）、其他錯誤
     */
    public byte[] completeUnlock(String vaultId, Cipher authenticated) throws BioException {
        String id = normalize(vaultId);
        BioSecretStore.Sealed sealed = store.get(id);
        if (sealed == null) {
            throw new BioException(BioReason.NOT_ENABLED);
        }
        try {
            authenticated.updateAAD(aad(id));
            return authenticated.doFinal(sealed.ciphertext());
        } catch (AEADBadTagException e) {
            disableQuietly(id);
            throw new BioException(BioReason.CORRUPT_DATA, e);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new BioException(BioReason.FAILED, e);
        }
    }

    /**
     * 停用一個保管庫的生物辨識解鎖：刪除金鑰與密文（停用功能、變更主密碼時，規格 §1）。
     *
     * @param vaultId 保管庫 ID
     * @throws BioException 金鑰庫錯誤（密文仍會刪除）
     */
    public void disable(String vaultId) throws BioException {
        String id = normalize(vaultId);
        store.remove(id);
        keys.delete(alias(id));
    }

    /**
     * 刪除所有保管庫的金鑰與密文（模組停用 {@code modules.biometric.enabled=false} 時，規格 §1）。
     *
     * @throws BioException 金鑰庫錯誤（密文仍會刪除）
     */
    public void disableAll() throws BioException {
        for (String id : store.vaultIds()) {
            store.remove(id);
        }
        for (String alias : keys.aliases(ALIAS_PREFIX)) {
            keys.delete(alias);
        }
    }

    /**
     * 解鎖畫面要不要顯示按鈕（{@code UnlockProvider#isAvailable}，T-71）。外殼呼叫時不給 {@link Context}，所以只看
     * Keystore 有沒有這個保管庫的金鑰（不需要 Context）；啟用做到一半留下、沒有密文的金鑰也會讓按鈕出現，按下後由
     * {@link #prepareUnlock} 回報 {@link BioReason#NOT_ENABLED}、{@link BioUnlockFlow} 清掉。
     *
     * @param keys    金鑰庫
     * @param vaultId 保管庫 ID；{@code null} 或格式不對時回傳 {@code false}
     * @return 有金鑰時為 {@code true}
     */
    static boolean hasKey(BioKeys keys, String vaultId) {
        if (vaultId == null) {
            return false;
        }
        try {
            return keys.aliases(ALIAS_PREFIX).contains(alias(normalize(vaultId)));
        } catch (BioException | IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * @return 已啟用（有存密文）的保管庫數量（設定段落的狀態，T-71）
     */
    public int enabledCount() {
        return store.vaultIds().size();
    }

    /**
     * @return 有任何密文或金鑰（含啟用做到一半留下的金鑰）時為 {@code true}：設定段落的「停用」要不要出現
     * @throws BioException 金鑰庫或私有偏好讀取錯誤
     */
    public boolean hasAnyData() throws BioException {
        return !store.vaultIds().isEmpty() || !keys.aliases(ALIAS_PREFIX).isEmpty();
    }

    /**
     * @return 已啟用（有存密文）的保管庫 ID（小寫，設定段落的「另有 n 個保管庫」，T-72）
     */
    Set<String> enabledVaultIds() {
        return new HashSet<>(store.vaultIds());
    }

    /**
     * @return 有密文或金鑰（含啟用做到一半留下的金鑰）的保管庫 ID（小寫，設定段落的「停用所有保管庫」，T-72）
     * @throws BioException 金鑰庫讀取錯誤
     */
    Set<String> dataVaultIds() throws BioException {
        Set<String> ids = enabledVaultIds();
        for (String alias : keys.aliases(ALIAS_PREFIX)) {
            ids.add(alias.substring(ALIAS_PREFIX.length()));
        }
        return ids;
    }

    static String alias(String normalizedId) {
        return ALIAS_PREFIX + normalizedId;
    }

    /** 保管庫 ID 一律是小寫 UUID，避免大小寫不同對到兩把金鑰；格式不對直接拒絕，不拿來組別名或偏好鍵。 */
    static String normalize(String vaultId) {
        return UUID.fromString(vaultId).toString().toLowerCase(Locale.ROOT);
    }

    private static byte[] aad(String normalizedId) {
        return normalizedId.getBytes(StandardCharsets.UTF_8);
    }

    private void disableQuietly(String id) {
        store.remove(id);
        try {
            keys.delete(alias(id));
        } catch (BioException ignored) {
            // 已在回報另一個錯誤；殘留的金鑰在下次啟用時會被取代
        }
    }
}
