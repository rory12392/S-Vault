package app.svault.android.feature.biometric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.android.R;
import app.svault.android.shell.AndroidModules;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.android.shell.ext.SettingsSection;
import app.svault.android.shell.ext.UnlockProvider;
import app.svault.core.module.Contribution;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import java.util.List;
import java.util.Optional;
import org.junit.Test;

/** BIO 註冊的擴充點（T-71；AC-11-1）：解鎖畫面的按鈕與設定段落；模組停用時都不出現。 */
public class BiometricAndroidFeatureTest {

    @Test
    public void ac11_1_registersUnlockButtonAndSettingsSection() {
        BiometricAndroidFeature feature = new BiometricAndroidFeature();
        List<UnlockProvider> providers = feature.unlockProviders();
        assertEquals(1, providers.size());
        assertEquals(R.string.bio_unlock_button, providers.get(0).labelRes());
        List<SettingsSection> sections = feature.settingsSections();
        assertEquals(List.of(new SettingsSection(R.string.bio_settings_title, BioSettingsFragment.class)), sections);
    }

    @Test
    public void unlockButtonHiddenWithoutKey() {
        FakeBioKeys keys = new FakeBioKeys();
        BioUnlockProvider provider = new BioUnlockProvider(keys);
        assertFalse(provider.isAvailable("3f2504e0-4f89-11d3-9a0c-0305e82c3301"));
        assertFalse("外殼不知道保管庫 ID 時", provider.isAvailable(null));
    }

    @Test
    public void disabledModuleContributesNothing() {
        ModuleSettings off = id -> Optional.of(!BiometricAndroidFeature.ID.equals(id));
        ModuleRegistry<AndroidFeature> registry = AndroidModules.discover(off, (id, action, error) -> { });
        List<Contribution<UnlockProvider>> providers = registry.collect("unlockProviders",
                AndroidFeature::unlockProviders);
        assertTrue(providers.stream().noneMatch(c -> BiometricAndroidFeature.ID.equals(c.moduleId())));
        ModuleRegistry<AndroidFeature> on = AndroidModules.discover(ModuleSettings.DEFAULTS, (id, action, error) -> { });
        assertTrue(on.collect("unlockProviders", AndroidFeature::unlockProviders).stream()
                .anyMatch(c -> BiometricAndroidFeature.ID.equals(c.moduleId())));
    }
}
