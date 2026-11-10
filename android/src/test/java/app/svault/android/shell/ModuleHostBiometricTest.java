package app.svault.android.shell;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.ModuleHost;
import app.svault.core.module.EntryNavigator;
import app.svault.core.module.MasterPasswordCheck;
import app.svault.core.module.ModuleFailureListener;
import app.svault.core.module.ModuleGuard;
import app.svault.core.module.ModulePreferences;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.UnlockedEntries;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.junit.Test;

/**
 * android 給生物辨識模組的核心介面（T-35）：{@link ModuleHost#unlockedVaultId()}、{@link ModuleHost#verifyMasterPassword} 的
 * 預設實作（模組分支上的假 host 不用改）與 {@link AndroidFeature#onDisabled} 的停用通知。正式的 host（{@code SvaultApplication}）
 * 委派給 core 的 {@code ModuleVaultIdentity}，規則由 core {@code ModuleVaultIdentityTest} 測；接線（解鎖時接上、上鎖時作廢）
 * 待里程碑整合的儀器測試與 T-72 的手機卡走一遍。
 */
public class ModuleHostBiometricTest {

    private final ModuleHost fake = new ModuleHost() {
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

    /** T-35：只實作舊方法的假 host → 沒有解鎖的保管庫 ID。 */
    @Test
    public void defaultVaultIdIsEmpty() {
        assertEquals(Optional.empty(), fake.unlockedVaultId());
    }

    /** T-35：只實作舊方法的假 host → verifyMasterPassword 通知 LOCKED 一次（交給指定的 executor）。 */
    @Test
    public void defaultVerifyIsLocked() {
        List<Runnable> queued = new ArrayList<>();
        List<MasterPasswordCheck> results = new ArrayList<>();

        fake.verifyMasterPassword("biometric", "probe1234".getBytes(StandardCharsets.UTF_8),
                queued::add, results::add);

        assertEquals(List.of(), results);
        queued.forEach(Runnable::run);
        assertEquals(List.of(MasterPasswordCheck.rejected(MasterPasswordCheck.Status.LOCKED)), results);
    }

    /** T-35：停用通知只給停用中的模組，帶外殼的 Context；預設的 onDisabled 不做事。 */
    @Test
    public void onlyDisabledModulesAreNotified() {
        List<String> notified = new ArrayList<>();
        ModuleRegistry<AndroidFeature> registry = ModuleRegistry.of(
                List.of(recording("biometric", notified), recording("autofill", notified), plain("generator")),
                id -> id.equals("autofill") ? Optional.of(true) : Optional.of(false),
                new ModuleGuard(ModuleFailureListener.NONE));

        AndroidModules.notifyDisabled(registry, null);

        assertEquals(List.of("biometric"), notified);
    }

    private static AndroidFeature recording(String id, List<String> notified) {
        return new AndroidFeature() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }

            @Override
            public void onDisabled(Context context) {
                notified.add(id);
            }
        };
    }

    private static AndroidFeature plain(String id) {
        return new AndroidFeature() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String displayName() {
                return id;
            }
        };
    }
}
