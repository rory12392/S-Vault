package app.svault.android.shell;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.module.EntryBatch;
import app.svault.core.module.EntryBatchResult;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.UnlockedEntries;
import app.svault.core.vault.EntryDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * android {@link ModuleHost#addEntries}（T-33）：預設實作一直是上鎖，模組分支上的假 host 不用改。正式的 host
 * （{@code SvaultApplication}）委派給 core 的 {@code ModuleVaultWriter}，規則由 core {@code ModuleVaultWriterTest} 測；
 * 接線（解鎖時接上、存檔後條目畫面換新）待里程碑整合的儀器測試與 T-62 的手機卡走一遍。
 */
public class ModuleHostWriteTest {

    /** T-33：只實作舊方法的假 host → addEntries 通知 LOCKED 一次（交給指定的 executor）。 */
    @Test
    public void defaultIsLocked() {
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
        List<Runnable> queued = new ArrayList<>();
        List<EntryBatchResult> results = new ArrayList<>();

        fake.addEntries("importer", new EntryBatch("Chrome 匯入",
                List.of(new EntryDraft("網站", "user", "probe1234", "https://example.com", ""))),
                queued::add, results::add);

        assertEquals(List.of(), results);
        queued.forEach(Runnable::run);
        assertEquals(List.of(EntryBatchResult.rejected(EntryBatchResult.Status.LOCKED)), results);
    }
}
