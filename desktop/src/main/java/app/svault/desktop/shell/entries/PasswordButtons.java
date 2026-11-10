package app.svault.desktop.shell.entries;

import app.svault.core.module.Contribution;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import app.svault.desktop.shell.ext.DesktopFeature;
import app.svault.desktop.shell.ext.PasswordFieldAction;
import java.util.ArrayList;
import java.util.List;

/**
 * 編輯畫面密碼欄旁的按鈕，來自功能模組的 {@link PasswordFieldAction} 擴充點（GEN 的「產生」，Architecture §1.1）。
 * T-10 只接好擴充點：沒有模組註冊時是空的，不顯示任何按鈕；產生器本身在 M-03（T-41）。
 */
final class PasswordButtons {

    private PasswordButtons() {
    }

    /**
     * @param registry 探索到的 desktop 功能模組（停用的不算）
     * @return 按鈕；每個動作都包在 {@link ModuleGuard} 裡，模組出錯時回傳空、不影響編輯與存檔
     */
    static List<EntryEditPane.PasswordButton> from(ModuleRegistry<DesktopFeature> registry) {
        ModuleGuard guard = registry.guard();
        List<EntryEditPane.PasswordButton> out = new ArrayList<>();
        for (Contribution<PasswordFieldAction> c
                : registry.collect("passwordFieldActions", DesktopFeature::passwordFieldActions)) {
            String module = c.moduleId();
            PasswordFieldAction action = c.value();
            out.add(new EntryEditPane.PasswordButton(action.label(),
                    () -> guard.call(module, "passwordFieldAction", action.action())));
        }
        return List.copyOf(out);
    }
}
