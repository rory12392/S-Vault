package app.svault.android.feature.biometric;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 記憶體版的密文儲存；可放入格式錯誤的資料模擬損壞。 */
final class MemoryBioSecretStore implements BioSecretStore {

    final Map<String, Sealed> data = new HashMap<>();
    final Set<String> corrupt = new HashSet<>();
    /** 下一次 {@link #put} 丟例外（模擬寫不進私有偏好）。 */
    boolean failPut;

    @Override
    public void put(String vaultId, Sealed sealed) {
        if (failPut) {
            failPut = false;
            throw new IllegalStateException("測試：寫入失敗");
        }
        data.put(vaultId, sealed);
        corrupt.remove(vaultId);
    }

    @Override
    public Sealed get(String vaultId) throws BioException {
        if (corrupt.contains(vaultId)) {
            throw new BioException(BioReason.CORRUPT_DATA);
        }
        return data.get(vaultId);
    }

    @Override
    public void remove(String vaultId) {
        data.remove(vaultId);
        corrupt.remove(vaultId);
    }

    @Override
    public Set<String> vaultIds() {
        Set<String> ids = new HashSet<>(data.keySet());
        ids.addAll(corrupt);
        return ids;
    }
}
