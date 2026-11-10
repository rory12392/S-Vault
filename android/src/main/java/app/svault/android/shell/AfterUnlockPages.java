package app.svault.android.shell;

import android.app.Activity;
import android.content.Intent;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModuleRegistry;
import java.util.Optional;

/**
 * 外殼的「解鎖後提示」擴充點（{@link AndroidFeature#afterUnlockPage}）：首頁建立時依模組順序問一次，第一個有頁面的才開，
 * 免得解鎖後連跳好幾頁。模組出錯時由錯誤邊界記錄、略過。
 */
public final class AfterUnlockPages {

    private AfterUnlockPages() {
    }

    /**
     * @param activity 首頁（剛建立、不是旋轉後重建）
     * @param registry Android 功能模組（停用的不問）
     */
    public static void openFirst(Activity activity, ModuleRegistry<AndroidFeature> registry) {
        ModuleGuard guard = registry.guard();
        for (AndroidFeature module : registry.enabled()) {
            String id = guard.call(module.getClass().getName(), "id", module::id).orElse(null);
            if (id == null) {
                continue;
            }
            Optional<Intent> page = guard.call(id, "afterUnlockPage", () -> module.afterUnlockPage(activity))
                    .flatMap(p -> p);
            if (page.isPresent()) {
                guard.run(id, "openAfterUnlockPage", () -> activity.startActivity(page.get()));
                return;
            }
        }
    }
}
