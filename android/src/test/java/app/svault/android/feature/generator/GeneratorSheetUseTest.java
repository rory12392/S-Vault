package app.svault.android.feature.generator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.svault.android.shell.ext.PasswordFill;
import app.svault.core.feature.generator.CharClass;
import app.svault.core.feature.generator.GeneratorModel;
import app.svault.core.feature.generator.GeneratorSettings;
import app.svault.core.feature.generator.PasswordGenerator;
import app.svault.core.security.PasswordStrength;
import java.util.Arrays;
import org.junit.Test;

/**
 * T-44：底部 sheet 的「使用這個密碼」。交給外殼回填的是結果的副本（外殼填完清零），畫面上的結果不受影響；
 * 選項不合法時不回填。
 */
public class GeneratorSheetUseTest {

    private static GeneratorModel model() {
        return new GeneratorModel(GeneratorSettings.DEFAULTS, new PasswordGenerator(), pw -> PasswordStrength.STRONG,
                Runnable::run, Runnable::run, s -> { });
    }

    /** AC-04-1：使用這個密碼 → 外殼收到和畫面相同的密碼（副本），回填後陣列清零，畫面上的結果還在。 */
    @Test
    public void ac04_1_useHandsCopyToFill() {
        GeneratorModel m = model();
        String shown = m.display();
        FakeFill fill = new FakeFill(true);

        assertTrue(GeneratorSheet.use(m, fill));

        assertEquals(1, fill.calls);
        assertEquals(shown, fill.received);
        assertNotSame(fill.array, m.take().orElseThrow());
        assertTrue("外殼清零的是副本", isZero(fill.array));
        assertEquals("畫面上的結果不受影響", shown, m.display());
    }

    /** 外殼拒絕回填（已上鎖、畫面已重建）：照樣算「交出去了」，sheet 直接關閉；陣列一樣被清零。 */
    @Test
    public void ac04_useRefusedStillHandsOver() {
        GeneratorModel m = model();
        FakeFill fill = new FakeFill(false);

        assertTrue(GeneratorSheet.use(m, fill));

        assertEquals(1, fill.calls);
        assertTrue(isZero(fill.array));
    }

    /** AC-04-1：四種字元都不選＝選項不合法，「使用這個密碼」不回填。 */
    @Test
    public void ac04_1_invalidDoesNotFill() {
        GeneratorModel m = model();
        for (CharClass c : CharClass.values()) {
            m.setClass(c, false);
        }
        FakeFill fill = new FakeFill(true);

        assertFalse(GeneratorSheet.use(m, fill));

        assertEquals(0, fill.calls);
        assertNull(fill.array);
    }

    /** 已清掉（sheet 關閉後）的模型不回填。 */
    @Test
    public void clearedDoesNotFill() {
        GeneratorModel m = model();
        m.clear();
        FakeFill fill = new FakeFill(true);

        assertFalse(GeneratorSheet.use(m, fill));
        assertEquals(0, fill.calls);
    }

    private static boolean isZero(char[] a) {
        for (char c : a) {
            if (c != '\0') {
                return false;
            }
        }
        return true;
    }

    /** 照外殼的約定：記下收到的內容後，不論填不填都清零。 */
    static final class FakeFill implements PasswordFill {

        private final boolean accept;
        int calls;
        String received;
        char[] array;

        FakeFill(boolean accept) {
            this.accept = accept;
        }

        @Override
        public boolean fill(char[] value) {
            calls++;
            array = value;
            received = new String(value);
            Arrays.fill(value, '\0');
            return accept;
        }

        @Override
        public boolean isActive() {
            return accept;
        }
    }
}
