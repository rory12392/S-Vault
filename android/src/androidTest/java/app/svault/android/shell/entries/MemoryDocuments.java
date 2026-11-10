package app.svault.android.shell.entries;

import app.svault.android.platform.DocumentAccess;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 儀器測試用的假文件（T-36）：記憶體中的 Uri → 內容，都當作有長期權限。真的解鎖畫面（{@code UnlockActivity}）經
 * {@code SafVaultLocation} 讀檔，要有 SAF 的長期權限，測試的文件提供者給不了，所以換成這個
 * （{@code StartActions.replaceDocuments}）。
 */
final class MemoryDocuments implements DocumentAccess {

    private final Map<String, byte[]> files = new ConcurrentHashMap<>();

    /**
     * @param uri  文件 Uri
     * @param data 內容
     */
    void put(String uri, byte[] data) {
        files.put(uri, data.clone());
    }

    @Override
    public String queryDisplayName(String uri) {
        return files.containsKey(uri) ? uri.substring(uri.lastIndexOf('/') + 1) : null;
    }

    @Override
    public InputStream openRead(String uri) throws FileNotFoundException {
        byte[] data = files.get(uri);
        if (data == null) {
            throw new FileNotFoundException(uri);
        }
        return new ByteArrayInputStream(data);
    }

    @Override
    public OutputStream openOverwrite(String uri) throws FileNotFoundException {
        if (!files.containsKey(uri)) {
            throw new FileNotFoundException(uri);
        }
        return new ByteArrayOutputStream() {
            @Override
            public void close() {
                files.put(uri, toByteArray());
            }
        };
    }

    @Override
    public boolean hasPersistedPermission(String uri) {
        return files.containsKey(uri);
    }

    @Override
    public void takePersistedPermission(String uri) {
    }

    @Override
    public void releasePersistedPermission(String uri) {
    }

    @Override
    public boolean deleteDocument(String uri) {
        return files.remove(uri) != null;
    }
}
