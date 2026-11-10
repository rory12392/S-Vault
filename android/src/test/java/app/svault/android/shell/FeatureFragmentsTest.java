package app.svault.android.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.fragment.app.Fragment;
import java.util.Optional;
import org.junit.Test;

/**
 * FeatureHostActivity 只載入真的是 Fragment 的類別（Intent 內容不可信任時也不會載入任意類別）。
 */
public class FeatureFragmentsTest {

    /** 測試用的模組頁面。 */
    public static class SamplePage extends Fragment {
    }

    private final ClassLoader loader = getClass().getClassLoader();

    @Test
    public void resolvesFragmentClass() {
        Optional<Class<? extends Fragment>> resolved = FeatureFragments.resolve(SamplePage.class.getName(), loader);
        assertTrue(resolved.isPresent());
        assertEquals(SamplePage.class, resolved.get());
    }

    @Test
    public void rejectsNonFragmentMissingAndEmpty() {
        assertFalse(FeatureFragments.resolve(String.class.getName(), loader).isPresent());
        assertFalse(FeatureFragments.resolve("app.svault.android.NoSuchPage", loader).isPresent());
        assertFalse(FeatureFragments.resolve("", loader).isPresent());
        assertFalse(FeatureFragments.resolve(null, loader).isPresent());
    }

    @Test
    public void rejectsAbstractFragment() {
        assertFalse(FeatureFragments.resolve(Fragment.class.getName() + "X", loader).isPresent());
        assertFalse(FeatureFragments.resolve(AbstractPage.class.getName(), loader).isPresent());
    }

    /** 不能建立實例的頁面。 */
    public abstract static class AbstractPage extends Fragment {
    }
}
