package app.svault.android.platform;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 記憶體中的 SAF 文件提供者，用來模擬 Drive 上會遇到的狀況：權限被撤銷、離線且沒有快取、檔案被刪除、
 * 覆寫到一半中斷、提供者不理會 {@code "wt"} 的截斷。
 */
public final class FakeDocuments implements DocumentAccess {

    private final Map<String, byte[]> contents = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    /** 檔案選擇器這一次給的暫時權限（可以轉成長期權限）。 */
    private final Set<String> granted = new HashSet<>();
    private final Set<String> persisted = new HashSet<>();

    /** 離線且沒有快取：查得到名稱，但打不開內容。 */
    public boolean offline;
    /** 在 Drive 的垃圾桶裡（T-106 實測）：查得到名稱、讀不到內容、寫入串流開得出來，{@link #looksTrashed} 為 true。 */
    public boolean trashed;
    /** 覆寫時寫到第幾個位元組就丟 IOException（模擬中斷）；負數表示不中斷。 */
    public int failWriteAfter = -1;
    /** 提供者不理會 {@code "wt"} 的截斷：新內容蓋在舊內容前面，舊內容較長的部分留著。 */
    boolean ignoresTruncate;
    /** 讀取時回傳的內容和實際存的不同（模擬提供者回傳舊的快取）。 */
    byte[] staleRead;
    /**
     * 截斷較晚才生效（Drive 實測，T-11）：寫入較短的內容後，接下來這麼多次讀取仍看到「新內容＋舊檔尾」。
     */
    int lateTruncateReads;
    private byte[] lateTail;
    private int lateReadsLeft;
    int writes;

    /** 使用者從檔案選擇器選了這個檔案。 */
    public String pick(String uri, String name, byte[] content) {
        contents.put(uri, content.clone());
        names.put(uri, name);
        granted.add(uri);
        return uri;
    }

    /** 使用者在系統設定或 Drive 撤銷了權限。 */
    public void revoke(String uri) {
        granted.remove(uri);
        persisted.remove(uri);
    }

    /** 檔案在另一台裝置被刪除。 */
    public void delete(String uri) {
        contents.remove(uri);
        names.remove(uri);
    }

    public byte[] content(String uri) {
        byte[] c = contents.get(uri);
        return c == null ? null : c.clone();
    }

    void setContent(String uri, byte[] content) {
        contents.put(uri, content.clone());
    }

    public boolean isPersisted(String uri) {
        return persisted.contains(uri);
    }

    private void checkAccess(String uri) {
        if (!granted.contains(uri) && !persisted.contains(uri)) {
            throw new SecurityException("Permission Denial: 測試");
        }
    }

    @Override
    public String queryDisplayName(String uri) {
        checkAccess(uri);
        return names.get(uri);
    }

    @Override
    public InputStream openRead(String uri) throws IOException {
        checkAccess(uri);
        if (!contents.containsKey(uri) || offline || trashed) {
            throw new FileNotFoundException("測試：" + (offline ? "離線" : trashed ? "垃圾桶" : "不存在"));
        }
        byte[] now = contents.get(uri);
        if (lateReadsLeft > 0 && lateTail != null && lateTail.length > now.length) {
            lateReadsLeft--;
            byte[] merged = Arrays.copyOf(lateTail, lateTail.length);
            System.arraycopy(now, 0, merged, 0, now.length);
            return new ByteArrayInputStream(merged);
        }
        return new ByteArrayInputStream(staleRead != null ? staleRead : now);
    }

    @Override
    public OutputStream openOverwrite(String uri) throws IOException {
        checkAccess(uri);
        if (!contents.containsKey(uri)) {
            throw new FileNotFoundException("測試：不存在");
        }
        if (offline) {
            throw new IOException("測試：離線");
        }
        writes++;
        byte[] old = contents.get(uri);
        lateTail = old;
        lateReadsLeft = lateTruncateReads;
        if (!ignoresTruncate) {
            contents.put(uri, new byte[0]);
        }
        return new OutputStream() {
            private final ByteArrayOutputStream written = new ByteArrayOutputStream();

            @Override
            public void write(int b) throws IOException {
                if (failWriteAfter >= 0 && written.size() >= failWriteAfter) {
                    throw new IOException("測試：寫到一半中斷");
                }
                written.write(b);
                // 每個位元組都立刻落到「遠端」：中斷時留下的就是寫到一半的檔案
                byte[] now = written.toByteArray();
                if (ignoresTruncate && old.length > now.length) {
                    byte[] merged = Arrays.copyOf(old, old.length);
                    System.arraycopy(now, 0, merged, 0, now.length);
                    contents.put(uri, merged);
                } else {
                    contents.put(uri, now);
                }
            }
        };
    }

    @Override
    public boolean looksTrashed(String uri) {
        return trashed;
    }

    @Override
    public boolean hasPersistedPermission(String uri) {
        return persisted.contains(uri);
    }

    @Override
    public void takePersistedPermission(String uri) {
        if (!granted.contains(uri)) {
            throw new SecurityException("No persistable permission grants found: 測試");
        }
        persisted.add(uri);
    }

    @Override
    public void releasePersistedPermission(String uri) {
        persisted.remove(uri);
    }

    @Override
    public boolean deleteDocument(String uri) {
        checkAccess(uri);
        boolean existed = contents.remove(uri) != null;
        names.remove(uri);
        return existed;
    }

    /** 模擬 App 重新啟動：選擇器給的暫時權限消失，只剩長期權限。 */
    public void restartApp() {
        granted.clear();
    }
}
