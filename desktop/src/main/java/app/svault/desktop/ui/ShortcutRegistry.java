package app.svault.desktop.ui;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import javafx.event.EventHandler;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;

/**
 * 快捷鍵註冊：每個場景一份，畫面切換時綁定或解除自己用到的快捷鍵。
 *
 * <p>以場景的 {@code KEY_PRESSED} 處理器（冒泡階段）實作，所以焦點元件自己處理掉的按鍵（例如文字欄的 Ctrl+C）
 * 不會再觸發；{@link Shortcut#yieldsToTextInput()} 的快捷鍵另外檢查焦點，確保不搶文字編輯。
 * 同一個快捷鍵同時只能綁一個動作，重複綁定直接拋例外，避免兩個畫面互搶。
 */
public final class ShortcutRegistry {

    private static final String KEY = ShortcutRegistry.class.getName();

    private final Scene scene;
    private final Map<Shortcut, Runnable> actions = new EnumMap<>(Shortcut.class);
    /** 暫停期間仍有效的快捷鍵；{@code null}＝沒有暫停。 */
    private Set<Shortcut> active;

    private ShortcutRegistry(Scene scene) {
        this.scene = scene;
        EventHandler<KeyEvent> handler = this::onKey;
        scene.addEventHandler(KeyEvent.KEY_PRESSED, handler);
    }

    /**
     * @param scene 場景
     * @return 這個場景的註冊表（第一次呼叫時建立）
     */
    public static ShortcutRegistry of(Scene scene) {
        return (ShortcutRegistry) scene.getProperties().computeIfAbsent(KEY, k -> new ShortcutRegistry(scene));
    }

    /**
     * @param shortcut 快捷鍵
     * @param action   動作
     * @throws IllegalStateException 已經綁定時
     */
    public void bind(Shortcut shortcut, Runnable action) {
        if (actions.putIfAbsent(shortcut, action) != null) {
            throw new IllegalStateException("快捷鍵已綁定：" + shortcut);
        }
    }

    /** @param shortcut 要解除的快捷鍵（沒綁定時不做事） */
    public void unbind(Shortcut shortcut) {
        actions.remove(shortcut);
    }

    /** @param shortcut 快捷鍵 @return 是否已綁定 */
    public boolean isBound(Shortcut shortcut) {
        return actions.containsKey(shortcut);
    }

    /**
     * 暫停其他快捷鍵（例：設定頁蓋住條目清單與詳細時，Ctrl+N 不能在看不到的編輯畫面動作，T-23）。
     * 綁定照舊保留，{@link #resume()} 後恢復。
     *
     * @param keep 暫停期間仍然有效的快捷鍵
     */
    public void suspendExcept(Set<Shortcut> keep) {
        Set<Shortcut> now = EnumSet.noneOf(Shortcut.class);
        now.addAll(keep);
        active = now;
    }

    /** 恢復全部快捷鍵。 */
    public void resume() {
        active = null;
    }

    /** @param shortcut 快捷鍵 @return 目前按下時會不會觸發（已綁定且沒有被暫停） */
    public boolean isActive(Shortcut shortcut) {
        return actions.containsKey(shortcut) && (active == null || active.contains(shortcut));
    }

    private void onKey(KeyEvent e) {
        if (e.isConsumed()) {
            return;
        }
        for (Map.Entry<Shortcut, Runnable> entry : actions.entrySet()) {
            Shortcut shortcut = entry.getKey();
            if (shortcut.combination().match(e)) {
                if (!isActive(shortcut)) {
                    return;
                }
                if (shortcut.yieldsToTextInput() && focusIsTextInput()) {
                    return;
                }
                entry.getValue().run();
                e.consume();
                return;
            }
        }
    }

    private boolean focusIsTextInput() {
        Node focus = scene.getFocusOwner();
        return focus instanceof TextInputControl;
    }
}
