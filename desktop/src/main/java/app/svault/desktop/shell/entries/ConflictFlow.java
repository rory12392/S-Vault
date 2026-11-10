package app.svault.desktop.shell.entries;

import app.svault.core.shell.ConflictChoices;
import app.svault.core.sync.ConflictView;
import app.svault.core.sync.Side;
import app.svault.desktop.ui.Toast;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.UUID;

/**
 * 衝突的處理流程（T-15；US-08）：Banner「有 n 筆衝突需要處理」的「處理」→ 在背景取得衝突（{@code VaultOps.conflicts()}）
 * → {@link ConflictDialog} → 選完時在背景 {@code VaultOps.resolve}（存檔流程同 US-07）→ Toast「已解決衝突並儲存」。
 * 關閉對照畫面不做任何寫入，Banner 留著（AC-08-3）。
 *
 * <p>記著上一次的選擇（包含沒選完就關閉、或寫回時離線）：再開時兩邊的值沒變的欄位直接帶入。上鎖時交給
 * {@code VaultOps.lock} 加密保存（AC-08-5），下次解鎖後 {@link #resume()} 回到對照畫面。
 */
final class ConflictFlow {

    private final VaultScreen screen;
    private final ResourceBundle text;
    private final ZoneId zone;
    private ConflictView lastView;
    private Map<UUID, Map<String, Side>> lastChoices;
    /** 對照畫面開著時畫面上的選擇（自動上鎖會先關掉對話框，選擇從這裡取，T-18）。 */
    private ConflictChoices showing;

    /**
     * @param screen 三欄畫面
     * @param text   字串
     * @param zone   修改時間的時區
     */
    ConflictFlow(VaultScreen screen, ResourceBundle text, ZoneId zone) {
        this.screen = screen;
        this.text = text;
        this.zone = zone;
    }

    /** 開啟對照畫面；寫入進行中、或編輯中而使用者選擇繼續編輯時不開。 */
    void open() {
        if (screen.busy() || !screen.editFlow().leaveEdit()) {
            return;
        }
        screen.read(screen.ops()::conflicts, this::show);
    }

    /**
     * 解鎖後第一次更新發現仍有衝突：上次衝突沒解決就上鎖時，直接回到對照畫面並帶入當時的選擇（AC-08-5）。
     */
    void resume() {
        screen.read(screen.ops()::resumeConflicts, found -> found.ifPresent(choices -> {
            if (!screen.busy() && !screen.editFlow().editing()) {
                display(choices);
            }
        }));
    }

    /**
     * @return 上鎖時要加密保存的選擇（對照畫面開著時是畫面上目前的，AC-08-5）；沒開過時是空的
     */
    Map<UUID, Map<String, Side>> choicesForLock() {
        if (showing != null) {
            return showing.toMap();
        }
        return lastChoices == null ? Map.of() : lastChoices;
    }

    /** 上鎖：丟掉記著的內容。 */
    void clear() {
        lastView = null;
        lastChoices = null;
        showing = null;
    }

    private void show(Optional<ConflictView> found) {
        if (found.isEmpty() || found.get().items().isEmpty()) {
            // 這之間已經解決（例如自動重試時另一台的修改讓衝突消失）：重新讀一次狀態
            screen.sync().syncNow();
            return;
        }
        display(new ConflictChoices(found.get(), lastView, lastChoices));
    }

    private void display(ConflictChoices choices) {
        ConflictDialog dialog = new ConflictDialog(screen.list(), text, choices, zone);
        lastView = choices.view();
        showing = choices;
        Optional<Map<UUID, Map<String, Side>>> chosen;
        try {
            chosen = dialog.showAndWait();
        } finally {
            showing = null;
        }
        finish(choices, chosen);
    }

    private void finish(ConflictChoices choices, Optional<Map<UUID, Map<String, Side>>> chosen) {
        lastChoices = choices.toMap();
        if (chosen.isEmpty()) {
            // AC-08-3：不寫遠端，Banner「有 n 筆衝突需要處理」維持
            return;
        }
        if (screen.busy()) {
            // 對話框開著時開始了別的寫入（不太會發生）：選擇記著，再按一次「處理」就會帶入
            return;
        }
        screen.sync().resolving(true);
        screen.apply(() -> screen.ops().resolve(chosen.get()), null, out -> {
            if (out.saved()) {
                clear();
                screen.shell().toasts().show(Toast.Kind.SUCCESS, text.getString("toast.resolved"));
            }
            // 「處理」按鈕所在的 Banner 已換掉或移除，焦點回到條目清單（DESIGN §3：對話框關閉後焦點回到觸發的地方）
            screen.list().focusDefault();
        }, () -> screen.sync().resolving(false));
    }
}
