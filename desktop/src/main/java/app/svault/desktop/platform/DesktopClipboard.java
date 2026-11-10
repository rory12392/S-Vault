package app.svault.desktop.platform;

import app.svault.core.session.ClipboardGuard;
import app.svault.core.session.ClipboardPort;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.util.Duration;

/**
 * 系統剪貼簿（core 的 {@link ClipboardPort}，T-19；只在 JavaFX 執行緒使用）。什麼時候清除由 {@link ClipboardGuard} 決定
 * （設定的秒數、上鎖時立即）；這裡清除前先比對：剪貼簿內容仍是那個密碼才清，使用者已經複製了別的東西就不動
 * （AC-05-2）。
 */
public final class DesktopClipboard implements ClipboardPort {

    private final Clipboard clipboard;

    /** 系統剪貼簿。 */
    public DesktopClipboard() {
        this(Clipboard.getSystemClipboard());
    }

    DesktopClipboard(Clipboard clipboard) {
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
    }

    /**
     * @return JavaFX 執行緒上的計時器（給 {@link ClipboardGuard}）
     */
    public static ClipboardGuard.Scheduler scheduler() {
        return (delay, task) -> {
            PauseTransition t = new PauseTransition(Duration.millis(delay.toMillis()));
            t.setOnFinished(e -> task.run());
            t.play();
            return t::stop;
        };
    }

    @Override
    public void copy(String value, boolean sensitive) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value == null ? "" : value);
        if (sensitive) {
            // Windows 的剪貼簿歷程記錄（Win+V）與雲端剪貼簿會另外留一份，清除剪貼簿也刪不掉：密碼加上 Windows 定義的
            // 三個格式，請它們不要收（同 KeePassXC；值是 DWORD，little-endian）
            content.put(format("ExcludeClipboardContentFromMonitorProcessing"), dword(1));
            content.put(format("CanIncludeInClipboardHistory"), dword(0));
            content.put(format("CanUploadToCloudClipboard"), dword(0));
        }
        clipboard.setContent(content);
    }

    private static DataFormat format(String windowsName) {
        DataFormat existing = DataFormat.lookupMimeType(windowsName);
        return existing != null ? existing : new DataFormat(windowsName);
    }

    // JavaFX 在 Windows 把 ByteBuffer 原樣放進自訂格式（其他型別會序列化成 Java 物件）
    private static ByteBuffer dword(int value) {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0, value);
    }

    @Override
    public void clearSecret(String secret) {
        // 只比對、不保留：Java 字串無法清零，比對完由 ClipboardGuard 丟掉參照（Architecture §10）
        if (secret.equals(clipboard.getString())) {
            clipboard.clear();
        }
    }
}
