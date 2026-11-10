package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.Test;

/**
 * I-5：一般 App（{@code androidapp://}）第一次被自動填入時記下簽署憑證，之後換了憑證就不再自動交出帳密。
 */
public class AppSignerPinsTest {

    private final Map<String, String> saved = new HashMap<>();
    private final AppSignerPins pins = new AppSignerPins(new AppSignerPins.Store() {
        @Override
        public Optional<String> get(String key) {
            return Optional.ofNullable(saved.get(key));
        }

        @Override
        public void put(String key, String value) {
            saved.put(key, value);
        }
    });

    @Test
    public void firstUseIsPinnedThenSameSignerIsTrusted() {
        assertTrue(pins.trusted("com.bank.app", Set.of("AA")));
        assertEquals(1, saved.size());
        assertTrue(pins.trusted("com.bank.app", Set.of("AA", "BB")));
    }

    @Test
    public void unknownSignerIsNotTrustedEvenTheFirstTime() {
        // Codex B1：查不到憑證（沒安裝、看不到）時不放行，也不留紀錄；之後查得到時照常第一次記下
        assertFalse(pins.trusted("com.bank.app", Set.of()));
        assertTrue(saved.isEmpty());
        assertTrue(pins.trusted("com.bank.app", Set.of("AA")));
    }

    @Test
    public void changedSignerIsNotTrusted() {
        pins.trusted("com.bank.app", Set.of("AA"));
        assertFalse(pins.trusted("com.bank.app", Set.of("CC")));
        assertFalse("查不到憑證也不算", pins.trusted("com.bank.app", Set.of()));
    }

    @Test
    public void storeHoldsNoPackageNameOrCertificate() {
        pins.trusted("com.bank.app", Set.of("F0FD6C5B410F25CB"));
        String key = saved.keySet().iterator().next();
        String value = saved.values().iterator().next();
        assertFalse(key.contains("bank"));
        assertFalse(value.contains("F0FD6C5B410F25CB"));
    }

    @Test
    public void plannerOffersNothingWhenAppSignerChanged() {
        FillPlannerTest t = new FillPlannerTest();
        Signers first = pkg -> Set.of("AA");
        Signers later = pkg -> Set.of("CC");
        assertEquals(FillPlan.Outcome.ENTRIES, t.planWith(first, pins, "com.bank.app").outcome());
        assertEquals(FillPlan.Outcome.SEARCH_ONLY, t.planWith(later, pins, "com.bank.app").outcome());
    }
}
