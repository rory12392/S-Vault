package app.svault.core.storage;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 覆寫遠端前的備份：{@code backups/<保管庫ID>/<yyyyMMdd-HHmmss>.kdbx}，最多 {@value #MAX_BACKUPS} 份
 * （Architecture §2.2、AC-09-1）。備份是原檔位元組的直接複製，所以和原檔一樣是加密的 KDBX（AC-09-4）。
 *
 * <p>同一秒內第二次備份的檔名加 {@code _2}、{@code _3}…，排序以時間再以序號比較。
 */
public final class BackupStore {

    /** 每個保管庫保留的備份份數（AC-09-1）。 */
    public static final int MAX_BACKUPS = 10;

    private static final DateTimeFormatter NAME_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);
    private static final Pattern NAME = Pattern.compile("(\\d{8}-\\d{6})(?:_(\\d{1,4}))?\\.kdbx");

    /** 一份備份。 */
    public record Backup(Path file, LocalDateTime time) {
    }

    // 排序用：時間相同時比序號（沒有序號＝1）
    private record Named(Path file, LocalDateTime time, int seq) {
    }

    private final Path root;
    private final Clock clock;

    /**
     * @param root  {@code backups/} 資料夾
     * @param clock 決定備份檔名的時鐘（時區用時鐘的時區）
     */
    public BackupStore(Path root, Clock clock) {
        this.root = Objects.requireNonNull(root, "root");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 存一份備份，再刪掉超過 {@value #MAX_BACKUPS} 份的最舊備份。
     *
     * @param vaultId 保管庫 ID
     * @param data    要備份的檔案位元組（遠端目前的內容）
     * @return 新備份的路徑
     * @throws IOException 寫入失敗
     */
    public Path backup(UUID vaultId, byte[] data) throws IOException {
        Path dir = root.resolve(vaultId.toString());
        Files.createDirectories(dir);
        String stamp = LocalDateTime.now(clock).format(NAME_TIME);
        Path file = dir.resolve(stamp + ".kdbx");
        for (int seq = 2; Files.exists(file) || Files.exists(AtomicFiles.tempFileOf(file)); seq++) {
            file = dir.resolve(stamp + "_" + seq + ".kdbx");
        }
        AtomicFiles.write(file, data);
        prune(dir);
        return file;
    }

    /**
     * @param vaultId 保管庫 ID
     * @return 這個保管庫的備份，最新的在前；沒有時為空
     * @throws IOException 無法列出資料夾
     */
    public List<Backup> list(UUID vaultId) throws IOException {
        List<Backup> out = new ArrayList<>();
        for (Named n : scan(root.resolve(vaultId.toString()))) {
            out.add(new Backup(n.file(), n.time()));
        }
        return Collections.unmodifiableList(out);
    }

    private void prune(Path dir) throws IOException {
        List<Named> all = scan(dir);
        for (int i = MAX_BACKUPS; i < all.size(); i++) {
            Files.deleteIfExists(all.get(i).file());
        }
    }

    /** @return 資料夾中符合備份檔名的檔案，最新的在前；不認得的檔案不列入（也就不會被刪） */
    private static List<Named> scan(Path dir) throws IOException {
        List<Named> found = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.kdbx")) {
            for (Path p : stream) {
                Matcher m = NAME.matcher(p.getFileName().toString());
                if (!m.matches()) {
                    continue;
                }
                try {
                    LocalDateTime time = LocalDateTime.parse(m.group(1), NAME_TIME);
                    int seq = m.group(2) == null ? 1 : Integer.parseInt(m.group(2));
                    found.add(new Named(p, time, seq));
                } catch (DateTimeParseException e) {
                    // 檔名像備份但日期不合法：不是我們寫的，跳過
                }
            }
        }
        found.sort(Comparator.comparing(Named::time).thenComparingInt(Named::seq).reversed());
        return found;
    }
}
