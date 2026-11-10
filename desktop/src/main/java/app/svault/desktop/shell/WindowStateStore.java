package app.svault.desktop.shell;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 記住主視窗的位置與大小：本機資料夾的 {@code window.properties}。
 * 只有幾個數字，不含任何機密；讀不到或內容不合理時當作沒有紀錄，不影響啟動。
 */
public final class WindowStateStore {

    /** 檔名。 */
    public static final String FILE_NAME = "window.properties";

    private static final Logger LOG = Logger.getLogger(WindowStateStore.class.getName());

    /**
     * @param x         左上角 x
     * @param y         左上角 y
     * @param width     寬
     * @param height    高
     * @param maximized 是否最大化
     */
    public record Bounds(double x, double y, double width, double height, boolean maximized) {

        /**
         * @param minWidth  最小寬
         * @param minHeight 最小高
         * @return 大小不小於最小值的版本
         */
        public Bounds atLeast(double minWidth, double minHeight) {
            return new Bounds(x, y, Math.max(width, minWidth), Math.max(height, minHeight), maximized);
        }
    }

    private final Path file;

    /**
     * @param dataDir 本機資料夾根目錄（{@code LocalStore.root()}）
     */
    public WindowStateStore(Path dataDir) {
        this.file = dataDir.resolve(FILE_NAME);
    }

    /**
     * @return 上次的位置與大小；沒有紀錄、讀取失敗或數值不合理時為空
     */
    public Optional<Bounds> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties p = new Properties();
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(in);
            Bounds b = new Bounds(number(p, "x"), number(p, "y"), number(p, "width"), number(p, "height"),
                    Boolean.parseBoolean(p.getProperty("maximized")));
            if (b.width() <= 0 || b.height() <= 0) {
                return Optional.empty();
            }
            return Optional.of(b);
        } catch (IOException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "視窗位置紀錄無法讀取，改用預設值：{0}", e.getClass().getName());
            return Optional.empty();
        }
    }

    /**
     * 先寫暫存檔再取代，避免寫到一半留下壞檔。
     *
     * @param bounds 位置與大小
     */
    public void save(Bounds bounds) {
        Properties p = new Properties();
        p.setProperty("x", Double.toString(bounds.x()));
        p.setProperty("y", Double.toString(bounds.y()));
        p.setProperty("width", Double.toString(bounds.width()));
        p.setProperty("height", Double.toString(bounds.height()));
        p.setProperty("maximized", Boolean.toString(bounds.maximized()));
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                p.store(out, null);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "視窗位置紀錄無法寫入：{0}", e.getClass().getName());
        }
    }

    private static double number(Properties p, String key) {
        String v = p.getProperty(key);
        if (v == null) {
            throw new IllegalArgumentException("缺少 " + key);
        }
        double d = Double.parseDouble(v.trim());
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("不合理的 " + key);
        }
        return d;
    }
}
