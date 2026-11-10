package app.svault.desktop.shell.gallery;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.CRC32;
import java.util.zip.DeflaterOutputStream;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 開發用截圖（STACK §6「截圖」、§7 第 7 點）：畫面穩定後把最上層視窗的場景存成 PNG 並結束程式。
 * 只截場景內容（不含 Windows 標題列）；標題列另以螢幕截圖確認。
 *
 * <p>不用 {@code javafx.swing} 的 {@code SwingFXUtils}，避免為了開發工具多一個相依：PNG 編碼只有幾十行。
 */
public final class Snapshot {

    private static final Logger LOG = Logger.getLogger(Snapshot.class.getName());
    // 等版面、CSS 與對話框都穩定（Toast、對話框在顯示後才建立）
    private static final Duration SETTLE = Duration.millis(1200);

    private Snapshot() {
    }

    /**
     * @param target 輸出的 PNG 檔
     */
    public static void saveAndExit(Path target) {
        PauseTransition wait = new PauseTransition(SETTLE);
        wait.setOnFinished(e -> {
            int code = 0;
            try {
                List<Window> showing = Window.getWindows().stream().filter(Window::isShowing).toList();
                Window top = showing.get(showing.size() - 1);
                WritableImage image = top.getScene().snapshot(null);
                write(image, target);
                LOG.log(Level.INFO, "截圖已存：{0}", target.getFileName());
            } catch (IOException | RuntimeException ex) {
                LOG.log(Level.WARNING, "截圖失敗：{0}", ex.getClass().getName());
                code = 1;
            }
            Platform.exit();
            if (code != 0) {
                System.exit(code);
            }
        });
        wait.play();
    }

    /**
     * 以 RGBA、每列 filter 0 寫出 PNG。
     *
     * @param image  影像
     * @param target 輸出檔
     * @throws IOException 寫入失敗時
     */
    static void write(WritableImage image, Path target) throws IOException {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        PixelReader reader = image.getPixelReader();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(raw)) {
            byte[] row = new byte[1 + w * 4];
            for (int y = 0; y < h; y++) {
                row[0] = 0;
                for (int x = 0; x < w; x++) {
                    int argb = reader.getArgb(x, y);
                    int i = 1 + x * 4;
                    row[i] = (byte) (argb >> 16);
                    row[i + 1] = (byte) (argb >> 8);
                    row[i + 2] = (byte) argb;
                    row[i + 3] = (byte) (argb >>> 24);
                }
                z.write(row);
            }
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        try (OutputStream out = Files.newOutputStream(target)) {
            out.write(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(ihdr);
            d.writeInt(w);
            d.writeInt(h);
            d.write(new byte[] {8, 6, 0, 0, 0});
            chunk(out, "IHDR", ihdr.toByteArray());
            chunk(out, "IDAT", raw.toByteArray());
            chunk(out, "IEND", new byte[0]);
        }
    }

    private static void chunk(OutputStream out, String type, byte[] data) throws IOException {
        DataOutputStream d = new DataOutputStream(out);
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        d.writeInt(data.length);
        d.write(t);
        d.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        d.writeInt((int) crc.getValue());
    }
}
