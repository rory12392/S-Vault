package app.svault.android.shell.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.fragment.app.Fragment;
import app.svault.android.shell.AndroidModules;
import app.svault.android.shell.ext.AndroidFeature;
import app.svault.core.module.ModuleRegistry;
import app.svault.core.module.ModuleSettings;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * 設定畫面的模組段落（{@code SettingsSection}）：正常的模組列出段落；
 * 整個擴充點丟例外時仍列一段（標題用模組名稱、內容是錯誤）；停用的模組不列。
 */
public class ModuleSectionsTest {

    private static final Set<String> FAKES = Set.of("alpha", "faulty");

    private final List<String> failures = new ArrayList<>();

    private ModuleRegistry<AndroidFeature> discover(ModuleSettings settings) {
        return AndroidModules.discover(settings, (id, action, error) -> failures.add(id + ":" + action));
    }

    /** 只看測試用的假模組：真的功能模組（例如 BIO，T-71）也會註冊段落，數量不固定。 */
    private static List<ModuleSections.Section> fakes(List<ModuleSections.Section> all) {
        return all.stream().filter(s -> FAKES.contains(s.moduleId())).collect(Collectors.toList());
    }

    @Test
    public void sectionsOfEnabledModules() {
        List<ModuleSections.Section> sections = fakes(ModuleSections.collect(discover(ModuleSettings.DEFAULTS)));
        assertEquals(sections.toString(), 2, sections.size());
        ModuleSections.Section alpha = sections.get(0);
        assertEquals("alpha", alpha.moduleId());
        assertEquals(1, alpha.titleRes());
        assertEquals(Optional.of(Fragment.class), alpha.page());

        // Faulty 的 settingsSections() 丟例外：仍有一段，標題是模組名稱、沒有內容
        ModuleSections.Section faulty = sections.get(1);
        assertEquals("faulty", faulty.moduleId());
        assertEquals(0, faulty.titleRes());
        assertEquals("Faulty", faulty.fallbackTitle());
        assertFalse(faulty.page().isPresent());
        assertTrue(failures.toString(), failures.contains("faulty:settingsSections"));
    }

    @Test
    public void disabledModuleIsNotListed() {
        ModuleSettings off = id -> Optional.of(!"alpha".equals(id) && !"faulty".equals(id));
        List<ModuleSections.Section> sections = fakes(ModuleSections.collect(discover(off)));
        assertTrue(sections.toString(), sections.isEmpty());
    }
}
