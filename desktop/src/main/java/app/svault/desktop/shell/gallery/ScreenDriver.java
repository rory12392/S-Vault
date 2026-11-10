package app.svault.desktop.shell.gallery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ObservableBooleanValue;
import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Labeled;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.stage.Window;
import javafx.util.Duration;

/**
 * 開發版的畫面腳本（{@code --drive=步驟|步驟|…}，STACK §6「截圖」）：在真的畫面上依序送出按鍵與輸入，
 * 用來截各種狀態與做鍵盤檢查。和 {@link KeyboardCheck} 一樣只在程式內派送事件，不動作業系統的輸入。
 * 按鍵、焦點與輸入都送到最上層的視窗（對話框開著時就是對話框）。
 *
 * <p>步驟：{@code wait=毫秒}、{@code idle}（等背景工作結束）、{@code focus=元件 id}、{@code type=文字}
 * （取代焦點所在文字欄的內容）、{@code typeEach=文字}（一個字一個字加在後面，記錄每個字到畫面更新完的時間，
 * AC-03-2）、{@code key=ENTER}（可加 {@code ctrl+}／{@code alt+}／{@code shift+}）、{@code perf=PAGE_DOWN}
 * （按鍵並記錄到畫面更新完的時間）、{@code tabs=n}（按 n 次 Tab 並記錄每次的焦點）、{@code pick=路徑}
 * （下一次選檔對話框的結果）、{@code hold=n}（卡住之後第 n 個背景工作，截「載入中」「儲存中」）、
 * {@code release}（放行）、{@code readonly=路徑}（把檔案設成唯讀，模擬存檔失敗；{@code writable=路徑} 改回）、
 * {@code corrupt=路徑}（把檔案截成一半，模擬覆寫中斷，T-12）、{@code menu=選單項目 id}（觸發工具列選單的項目，T-12）、
 * {@code sessionlock}（模擬 Windows 鎖定螢幕，T-18）、{@code log}（記錄焦點）、{@code shot=PNG 路徑}、{@code exit}。腳本只用 {@code .localdata} 的假資料。
 */
public final class ScreenDriver {

    private static final Logger LOG = Logger.getLogger(ScreenDriver.class.getName());
    // 每一步之後等版面與 runLater 跑完
    private static final Duration STEP = Duration.millis(200);
    private static final Duration POLL = Duration.millis(100);
    private static final long IDLE_TIMEOUT_MS = 60_000;

    private final Scene scene;
    private final ObservableBooleanValue busy;
    private final IntFunction<Runnable> hold;
    private final ScriptedDialogs dialogs;
    private final Runnable sessionLock;
    private final Deque<String> steps;
    private Runnable release = () -> { };
    private long idleSince;

    /**
     * @param scene   主視窗場景
     * @param busy    背景工作是否進行中
     * @param hold    卡住之後第 n 個背景工作，回傳放行
     * @param dialogs     腳本用的選檔對話框
     * @param sessionLock 模擬 Windows 鎖定螢幕（{@code sessionlock}，T-18）
     * @param script      {@code |} 分隔的步驟
     */
    public ScreenDriver(Scene scene, ObservableBooleanValue busy, IntFunction<Runnable> hold,
            ScriptedDialogs dialogs, Runnable sessionLock, String script) {
        this.scene = scene;
        this.busy = busy;
        this.hold = hold;
        this.dialogs = dialogs;
        this.sessionLock = sessionLock;
        this.steps = new ArrayDeque<>(Arrays.asList(script.split("\\|")));
    }

    /** 等畫面穩定後開始執行。 */
    public void start() {
        later(Duration.millis(800), this::next);
    }

    private void next() {
        String step = steps.pollFirst();
        if (step == null) {
            return;
        }
        int eq = step.indexOf('=');
        String name = eq < 0 ? step : step.substring(0, eq);
        String arg = eq < 0 ? "" : step.substring(eq + 1);
        try {
            switch (name) {
                case "wait" -> {
                    later(Duration.millis(Double.parseDouble(arg)), this::next);
                    return;
                }
                case "idle" -> {
                    idleSince = System.nanoTime();
                    waitIdle();
                    return;
                }
                case "focus" -> active().lookup("#" + arg).requestFocus();
                case "type" -> type(arg);
                case "typeEach" -> typeEach(arg);
                // 按鍵可能開模態對話框（showAndWait），不能在動畫計時器的回呼裡直接送
                case "key" -> Platform.runLater(() -> {
                    try {
                        press(arg);
                    } catch (RuntimeException e) {
                        log("步驟 key 失敗：" + e.getClass().getName());
                    }
                });
                case "perf" -> timed("按鍵 " + arg, () -> press(arg));
                case "tabs" -> {
                    for (int i = 1; i <= Integer.parseInt(arg); i++) {
                        press("TAB");
                        log("Tab " + i + " → " + describe(active().getFocusOwner()));
                    }
                }
                case "pick" -> dialogs.enqueue(Paths.get(arg));
                // 工具列選單的項目（MenuItem 不是場景中的節點，focus／key 碰不到）；會開模態對話框，同 key
                case "menu" -> Platform.runLater(() -> fireMenuItem(arg));
                case "hold" -> release = hold.apply(Integer.parseInt(arg));
                case "release" -> release.run();
                case "readonly" -> setReadOnly(Paths.get(arg), true);
                case "writable" -> setReadOnly(Paths.get(arg), false);
                case "corrupt" -> truncate(Paths.get(arg));
                case "sessionlock" -> sessionLock.run();
                // T-19：剪貼簿的狀態。只記「空／和指定的文字相同／不同」，不把內容寫進日誌
                case "clip" -> log("剪貼簿 → " + clipboardState(arg));
                case "setclip" -> {
                    javafx.scene.input.ClipboardContent c = new javafx.scene.input.ClipboardContent();
                    c.putString(arg);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(c);
                }
                case "log" -> log("焦點 → " + describe(active().getFocusOwner()));
                case "size" -> active().getRoot().lookupAll(arg).forEach(n -> log("尺寸 " + arg + " "
                        + n.getClass().getSimpleName() + " " + Math.round(n.getLayoutBounds().getWidth()) + "×"
                        + Math.round(n.getLayoutBounds().getHeight())));
                case "shot" -> shot(arg);
                case "exit" -> {
                    release.run();
                    Platform.exit();
                    return;
                }
                default -> log("不認得的步驟：" + name);
            }
        } catch (RuntimeException | IOException e) {
            log("步驟 " + name + " 失敗：" + e.getClass().getName());
        }
        later(STEP, this::next);
    }

    private void fireMenuItem(String id) {
        for (Node n : scene.getRoot().lookupAll(".menu-button")) {
            for (MenuItem item : ((MenuButton) n).getItems()) {
                if (id.equals(item.getId())) {
                    item.fire();
                    return;
                }
            }
        }
        log("找不到選單項目：" + id);
    }

    private void waitIdle() {
        boolean timedOut = (System.nanoTime() - idleSince) / 1_000_000 > IDLE_TIMEOUT_MS;
        if (busy.get() && !timedOut) {
            later(POLL, this::waitIdle);
            return;
        }
        log("背景工作結束，等了 " + (System.nanoTime() - idleSince) / 1_000_000 + " ms" + (timedOut ? "（逾時）" : ""));
        later(STEP, this::next);
    }

    /** 最上層的視窗（對話框開著時是對話框）的場景。 */
    private Scene active() {
        List<Window> showing = Window.getWindows().stream().filter(Window::isShowing).toList();
        return showing.isEmpty() ? scene : showing.get(showing.size() - 1).getScene();
    }

    private void type(String value) {
        if (active().getFocusOwner() instanceof TextInputControl t) {
            t.setText(value);
            t.positionCaret(value.length());
        } else {
            log("type：焦點不在文字欄（" + describe(active().getFocusOwner()) + "）");
        }
    }

    private void typeEach(String value) {
        if (!(active().getFocusOwner() instanceof TextInputControl t)) {
            log("typeEach：焦點不在文字欄（" + describe(active().getFocusOwner()) + "）");
            return;
        }
        long worst = 0;
        for (char c : value.toCharArray()) {
            worst = Math.max(worst, timed("輸入一個字", () -> {
                t.appendText(String.valueOf(c));
            }));
        }
        log("typeEach：" + value.length() + " 個字，最慢 " + worst + " ms");
    }

    /** 做一個動作，同步跑完 CSS 與排版（清單的 cell 在這時更新），記錄花了多久。 */
    private long timed(String what, Runnable action) {
        long start = System.nanoTime();
        action.run();
        Scene s = active();
        s.getRoot().applyCss();
        s.getRoot().layout();
        long ms = (System.nanoTime() - start) / 1_000_000;
        log(what + "：" + ms + " ms");
        return ms;
    }

    private void press(String spec) {
        List<String> parts = Arrays.asList(spec.toUpperCase(Locale.ROOT).split("\\+"));
        KeyCode code = KeyCode.valueOf(parts.get(parts.size() - 1));
        boolean ctrl = parts.contains("CTRL");
        boolean alt = parts.contains("ALT");
        boolean shift = parts.contains("SHIFT");
        Scene s = active();
        Node target = s.getFocusOwner() != null ? s.getFocusOwner() : s.getRoot();
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, false));
        // 按鈕的空白鍵在放開時才觸發；焦點可能已經因為按下而移動（或開了對話框），送給目前的焦點
        Scene after = active();
        Node now = after.getFocusOwner() != null ? after.getFocusOwner() : after.getRoot();
        Event.fireEvent(now, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, ctrl, alt, false));
    }

    private static void setReadOnly(Path file, boolean readOnly) throws IOException {
        Files.setAttribute(file, "dos:readonly", readOnly);
        log((readOnly ? "設成唯讀 " : "取消唯讀 ") + file.getFileName());
    }

    // 模擬覆寫途中中斷：檔案只剩前半（AC-09-5 的損壞提示）。只用在 .localdata 的假資料複本
    private static void truncate(Path file) throws IOException {
        byte[] data = Files.readAllBytes(file);
        Files.write(file, Arrays.copyOf(data, data.length / 2));
        log("截斷 " + file.getFileName());
    }

    private void shot(String file) {
        try {
            Snapshot.write(active().snapshot(null), Paths.get(file));
            log("截圖 " + Paths.get(file).getFileName());
        } catch (IOException e) {
            log("截圖失敗：" + e.getClass().getName());
        }
    }

    private static void later(Duration delay, Runnable action) {
        PauseTransition t = new PauseTransition(delay);
        t.setOnFinished(e -> action.run());
        t.play();
    }

    private static String clipboardState(String expected) {
        String now = javafx.scene.input.Clipboard.getSystemClipboard().getString();
        if (now == null || now.isEmpty()) {
            return "空";
        }
        return now.equals(expected) ? "和指定的文字相同" : "有其他文字";
    }

    private static void log(String line) {
        LOG.log(Level.INFO, "畫面腳本｜{0}", line);
    }

    private static String describe(Node n) {
        if (n == null) {
            return "（沒有焦點）";
        }
        String label = n.getId() != null ? "#" + n.getId() : "";
        String name = n.getAccessibleText();
        if (name == null && n instanceof Labeled l) {
            name = l.getText();
        }
        String state = n instanceof CheckBox c ? (c.isSelected() ? "（已勾選）" : "（未勾選）") : "";
        // 文字欄只記 id，不記內容（可能是主密碼或條目內容）
        return n.getClass().getSimpleName() + label + (name == null ? "" : "「" + name + "」") + state
                + (n.isFocused() ? "" : "（視窗沒有作業系統焦點）");
    }
}
