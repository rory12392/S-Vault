package app.svault.android.feature.biometric;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

/**
 * 把加密後的主密碼存在 App 私有偏好（檔名 {@code biometric}，鍵 {@code biometric.<保管庫ID>}，Architecture §2）。
 * 值的格式：{@code v1:<IV 的 Base64>:<密文的 Base64>}。這份偏好只有 Keystore 金鑰加密後的資料（AC-11-2）；
 * 不放進 {@code settings.json}（那份不加密、也會被匯出），也不經 {@code ModuleHost} 的模組設定。
 */
final class PrefsBioSecretStore implements BioSecretStore {

    static final String FILE = "biometric";
    static final String KEY_PREFIX = "biometric.";
    private static final String VERSION = "v1";

    private final SharedPreferences prefs;

    /**
     * @param context 任何 Context
     */
    PrefsBioSecretStore(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    @Override
    public void put(String vaultId, Sealed sealed) {
        Base64.Encoder enc = Base64.getEncoder();
        String value = VERSION + ":" + enc.encodeToString(sealed.iv()) + ":" + enc.encodeToString(sealed.ciphertext());
        // commit 而不是 apply：啟用完成時要確定已寫入，否則行程被殺會留下沒有密文的金鑰
        if (!prefs.edit().putString(KEY_PREFIX + vaultId, value).commit()) {
            throw new IllegalStateException("寫入私有偏好失敗");
        }
    }

    @Override
    public Sealed get(String vaultId) throws BioException {
        String value = prefs.getString(KEY_PREFIX + vaultId, null);
        if (value == null) {
            return null;
        }
        String[] parts = value.split(":", -1);
        if (parts.length != 3 || !VERSION.equals(parts[0])) {
            throw new BioException(BioReason.CORRUPT_DATA);
        }
        try {
            Base64.Decoder dec = Base64.getDecoder();
            return new Sealed(dec.decode(parts[1]), dec.decode(parts[2]));
        } catch (IllegalArgumentException e) {
            throw new BioException(BioReason.CORRUPT_DATA, e);
        }
    }

    @Override
    @SuppressLint("ApplySharedPref") // 停用與金鑰失效時要立刻從磁碟移除密文，不留給背景寫入
    public void remove(String vaultId) {
        prefs.edit().remove(KEY_PREFIX + vaultId).commit();
    }

    @Override
    public Set<String> vaultIds() {
        Set<String> ids = new HashSet<>();
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(KEY_PREFIX)) {
                ids.add(key.substring(KEY_PREFIX.length()));
            }
        }
        return ids;
    }
}
