package app.svault.core.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code recent.json}：最近開啟的保管庫（Architecture §2.2、AC-06-3），最新的在前，最多 {@value #MAX_ENTRIES} 筆。
 * 格式變更依 {@code schemaVersion} 升級（{@link VersionedJsonFile}）。
 *
 * <p>不是執行緒安全的；同一時間只由一個背景執行緒讀寫（{@code VaultSession} 的方法互斥）。
 */
public final class RecentStore {

    /** 目前的格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 最多記幾筆。 */
    public static final int MAX_ENTRIES = 10;

    private static final String VAULTS = "vaults";

    // 格式升級步驟：只新增、不改寫舊的步驟。版本 0＝沒有版本欄位的檔案，內容相同
    private static final List<VersionedJsonFile.Migration> MIGRATIONS = Collections.singletonList(
            new VersionedJsonFile.Migration(0, doc -> doc));

    private final VersionedJsonFile file;

    /**
     * @param file {@code recent.json} 的路徑
     */
    public RecentStore(Path file) {
        this.file = new VersionedJsonFile(file, SCHEMA_VERSION, MIGRATIONS);
    }

    /**
     * @return 最近開啟的保管庫，最新的在前；檔案不存在或無法解析時為空
     */
    public List<RecentVault> list() {
        Object vaults = file.read().get(VAULTS);
        List<RecentVault> out = new ArrayList<>();
        if (vaults instanceof List) {
            for (Object item : (List<?>) vaults) {
                RecentVault v = fromJson(item);
                if (v != null) {
                    out.add(v);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * @param location 位置字串
     * @return 該位置的紀錄
     */
    public Optional<RecentVault> find(String location) {
        for (RecentVault v : list()) {
            if (v.location().equals(location)) {
                return Optional.of(v);
            }
        }
        return Optional.empty();
    }

    /**
     * 記錄一次開啟：同一位置的舊紀錄被取代並移到最前面，超過上限的最舊紀錄被移除。
     *
     * @param vault 紀錄
     * @throws IOException 寫入失敗
     */
    public void record(RecentVault vault) throws IOException {
        List<RecentVault> list = new ArrayList<>(list());
        list.removeIf(v -> v.location().equals(vault.location()));
        list.add(0, vault);
        while (list.size() > MAX_ENTRIES) {
            list.remove(list.size() - 1);
        }
        save(list);
    }

    /**
     * 更新某個位置的遠端雜湊（存檔成功後）；沒有這個位置的紀錄時不做事。
     *
     * @param location 位置字串
     * @param hash     新的遠端雜湊
     * @throws IOException 寫入失敗
     */
    public void updateRemoteHash(String location, String hash) throws IOException {
        List<RecentVault> list = new ArrayList<>(list());
        boolean changed = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).location().equals(location)) {
                list.set(i, list.get(i).withRemoteHash(hash));
                changed = true;
            }
        }
        if (changed) {
            save(list);
        }
    }

    /**
     * @param location 要移除的位置（使用者從清單移除、檔案已不存在）
     * @throws IOException 寫入失敗
     */
    public void remove(String location) throws IOException {
        List<RecentVault> list = new ArrayList<>(list());
        if (list.removeIf(v -> v.location().equals(location))) {
            save(list);
        }
    }

    private void save(List<RecentVault> list) throws IOException {
        List<Object> vaults = new ArrayList<>();
        for (RecentVault v : list) {
            vaults.add(toJson(v));
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(VAULTS, vaults);
        file.write(doc);
    }

    private static Map<String, Object> toJson(RecentVault v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location", v.location());
        m.put("displayName", v.displayName());
        m.put("vaultId", v.vaultId().toString());
        m.put("lastOpened", v.lastOpened().toString());
        m.put("remoteHash", v.remoteHash());
        return m;
    }

    /** @return 一筆紀錄；欄位不完整或格式錯誤時為 {@code null}（略過那一筆，不讓整個清單壞掉） */
    private static RecentVault fromJson(Object item) {
        if (!(item instanceof Map)) {
            return null;
        }
        Map<?, ?> m = (Map<?, ?>) item;
        Object location = m.get("location");
        Object name = m.get("displayName");
        Object id = m.get("vaultId");
        Object opened = m.get("lastOpened");
        Object hash = m.get("remoteHash");
        if (!(location instanceof String && name instanceof String && id instanceof String
                && opened instanceof String)) {
            return null;
        }
        try {
            return new RecentVault((String) location, (String) name, UUID.fromString((String) id),
                    Instant.parse((String) opened), hash instanceof String ? (String) hash : null);
        } catch (IllegalArgumentException | DateTimeParseException e) {
            return null;
        }
    }
}
