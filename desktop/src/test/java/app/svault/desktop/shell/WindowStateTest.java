package app.svault.desktop.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import app.svault.desktop.shell.WindowStateStore.Bounds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 記住視窗大小與位置。
 */
class WindowStateTest {

    private static final Rectangle2D PRIMARY = new Rectangle2D(0, 0, 1920, 1040);

    @TempDir
    Path tmp;

    @Test
    @DisplayName("存檔後讀回同樣的位置、大小與最大化狀態")
    void roundTrip() {
        WindowStateStore store = new WindowStateStore(tmp);
        assertTrue(store.load().isEmpty());
        Bounds b = new Bounds(100, 50, 1300, 820, true);
        store.save(b);
        assertEquals(Optional.of(b), store.load());
        assertFalse(Files.exists(tmp.resolve(WindowStateStore.FILE_NAME + ".tmp")));
    }

    @Test
    @DisplayName("紀錄檔損壞或數值不合理時當作沒有紀錄")
    void corruptFile() throws IOException {
        Path f = tmp.resolve(WindowStateStore.FILE_NAME);
        Files.writeString(f, "x=abc\ny=1\nwidth=1\nheight=1\n", StandardCharsets.UTF_8);
        assertTrue(new WindowStateStore(tmp).load().isEmpty());
        Files.writeString(f, "x=1\ny=1\nwidth=-5\nheight=600\n", StandardCharsets.UTF_8);
        assertTrue(new WindowStateStore(tmp).load().isEmpty());
        Files.writeString(f, "x=1\ny=1\n", StandardCharsets.UTF_8);
        assertTrue(new WindowStateStore(tmp).load().isEmpty());
    }

    @Test
    @DisplayName("第一次開啟：1280×800 置中於主螢幕；螢幕太小時縮小但不小於 900×600")
    void defaultPlacement() {
        Bounds b = WindowPlacement.choose(Optional.empty(), List.of(PRIMARY), PRIMARY);
        assertEquals(new Bounds(320, 120, 1280, 800, false), b);
        Rectangle2D small = new Rectangle2D(0, 0, 1000, 700);
        Bounds s = WindowPlacement.choose(Optional.empty(), List.of(small), small);
        assertEquals(1000, s.width());
        assertEquals(700, s.height());
        Rectangle2D tiny = new Rectangle2D(0, 0, 800, 500);
        Bounds t = WindowPlacement.choose(Optional.empty(), List.of(tiny), tiny);
        assertEquals(AppShell.MIN_WIDTH, t.width());
        assertEquals(AppShell.MIN_HEIGHT, t.height());
    }

    @Test
    @DisplayName("還原上次的位置；上次的位置已不在任何螢幕上時改回預設位置")
    void restoreOrFallback() {
        Bounds saved = new Bounds(2000, 100, 1200, 700, false);
        Rectangle2D second = new Rectangle2D(1920, 0, 1920, 1040);
        assertEquals(saved, WindowPlacement.choose(Optional.of(saved), List.of(PRIMARY, second), PRIMARY));
        Bounds fallback = WindowPlacement.choose(Optional.of(saved), List.of(PRIMARY), PRIMARY);
        assertEquals(new Bounds(320, 120, 1280, 800, false), fallback);
    }

    @Test
    @DisplayName("紀錄的大小小於最小視窗時放大到 900×600")
    void enforceMinimum() {
        Bounds saved = new Bounds(10, 10, 400, 300, false);
        Bounds b = WindowPlacement.choose(Optional.of(saved), List.of(PRIMARY), PRIMARY);
        assertEquals(new Bounds(10, 10, AppShell.MIN_WIDTH, AppShell.MIN_HEIGHT, false), b);
    }
}
