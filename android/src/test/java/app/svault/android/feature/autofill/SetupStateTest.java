package app.svault.android.feature.autofill;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.svault.android.feature.autofill.SetupState.Action;
import app.svault.android.feature.autofill.SetupState.Service;
import org.junit.Test;

/** 啟用引導與設定段落要顯示什麼（T-82；AC-10-1）。 */
public class SetupStateTest {

    @Test
    public void ac10_1_detectsWhetherSvaultIsTheAutofillService() {
        assertEquals(Service.ENABLED, Service.of(true, true));
        assertEquals(Service.NOT_ENABLED, Service.of(true, false));
        // 系統不支援時不看第二個值（hasEnabledAutofillServices 在不支援的裝置上一律 false）
        assertEquals(Service.UNSUPPORTED, Service.of(false, false));
        assertEquals(Service.UNSUPPORTED, Service.of(false, true));
    }

    @Test
    public void ac10_1_firstTimeNotEnabledOpensTheGuide() {
        SetupState s = new SetupState(Service.NOT_ENABLED, false);
        assertEquals(Action.OPEN_GUIDE, s.primary());
        // 主要按鈕已經是引導：不另外列「說明」
        assertFalse(s.showGuideLink());
    }

    @Test
    public void us10_guideOpensOnceAfterFirstUnlock() {
        // T-105：首次解鎖後自動開引導——只在模組啟用、還沒選 S-Vault、還沒看過引導時
        assertTrue(new SetupState(Service.NOT_ENABLED, false).offerAfterUnlock(true));
        assertFalse("看過了", new SetupState(Service.NOT_ENABLED, true).offerAfterUnlock(true));
        assertFalse("模組停用", new SetupState(Service.NOT_ENABLED, false).offerAfterUnlock(false));
        assertFalse("已經是 S-Vault", new SetupState(Service.ENABLED, false).offerAfterUnlock(true));
        assertFalse("不支援", new SetupState(Service.UNSUPPORTED, false).offerAfterUnlock(true));
    }

    @Test
    public void ac10_1_afterTheGuideNotEnabledGoesStraightToSystemSettings() {
        SetupState s = new SetupState(Service.NOT_ENABLED, true);
        assertEquals(Action.OPEN_SYSTEM_SETTINGS, s.primary());
        assertTrue(s.showGuideLink());
    }

    @Test
    public void ac10_1_enabledNeedsNoAction() {
        for (boolean seen : new boolean[] {false, true}) {
            SetupState s = new SetupState(Service.ENABLED, seen);
            assertEquals(Action.NONE, s.primary());
            assertTrue(s.showGuideLink());
        }
    }

    @Test
    public void unsupportedDeviceOffersNothing() {
        for (boolean seen : new boolean[] {false, true}) {
            SetupState s = new SetupState(Service.UNSUPPORTED, seen);
            assertEquals(Action.NONE, s.primary());
            assertFalse(s.showGuideLink());
        }
    }

    @Test
    public void guidePageOffersSystemSettingsOnlyWhenNotEnabled() {
        assertTrue(SetupState.guideOffersSystemSettings(Service.NOT_ENABLED));
        assertFalse(SetupState.guideOffersSystemSettings(Service.ENABLED));
        assertFalse(SetupState.guideOffersSystemSettings(Service.UNSUPPORTED));
    }
}
