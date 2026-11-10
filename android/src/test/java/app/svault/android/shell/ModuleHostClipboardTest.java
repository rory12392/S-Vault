package app.svault.android.shell;

import static org.junit.Assert.assertFalse;

import android.content.Context;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * android {@link ModuleHost#clipboard}（T-34）：預設實作為空，模組分支上的假 host 不用改。正式的 host
 * （{@code SvaultApplication}）包的是條目畫面用的同一個 {@code ClipboardGuard}，規則由 core {@code ModuleClipboardsTest} 測；
 * 在手機上複製與清除待里程碑整合的儀器測試與 T-42 的手機卡走一遍。
 */
public class ModuleHostClipboardTest {

    /** T-34：只實作舊方法的假 host → clipboard 為空（模組不顯示「複製」）。 */
    @Test
    public void defaultIsEmpty() {
        ModuleHost fake = new ModuleHost() {
            @Override
            public ModulePreferences preferences(String moduleId) {
                return null;
            }

            @Override
            public EntryNavigator navigator(Context context) {
                return id -> { };
            }

            @Override
            public Optional<UnlockedEntries> unlockedEntries() {
                return Optional.empty();
            }

            @Override
            public boolean whenEntriesLoaded(Executor executor, Runnable callback) {
                return false;
            }
        };

        assertFalse(fake.clipboard("generator").isPresent());
    }
}
