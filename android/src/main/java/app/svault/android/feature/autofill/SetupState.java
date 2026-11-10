package app.svault.android.feature.autofill;

/**
 * 啟用引導與設定段落要顯示什麼（T-82；AC-10-1）。純 Java（JVM 測試）：系統的偵測在 {@link AutofillSetup}。
 *
 * <p>引導還沒看過、S-Vault 還不是自動填入服務時，設定段落的主要按鈕先開引導頁；看過之後直接開系統設定，另列「自動填入說明」
 * 可以再看一次。已啟用時不需要動作，只留說明（引導頁顯示「已是自動填入服務」）。裝置不支援時什麼都不能做。
 *
 * @param service   系統目前的狀態
 * @param guideSeen 引導頁看過了（{@code modules.autofill.guideSeen}）
 */
record SetupState(Service service, boolean guideSeen) {

    /** 系統的自動填入服務。 */
    enum Service {
        /** S-Vault 是目前的自動填入服務。 */
        ENABLED,
        /** 系統支援，但選的不是 S-Vault（或沒有選）。 */
        NOT_ENABLED,
        /** 系統沒有自動填入功能（{@code AutofillManager.isAutofillSupported()} 為 false）。 */
        UNSUPPORTED;

        /**
         * @param supported 系統支援自動填入
         * @param ours      {@code AutofillManager.hasEnabledAutofillServices()}：目前的服務是 S-Vault
         * @return 狀態
         */
        static Service of(boolean supported, boolean ours) {
            if (!supported) {
                return UNSUPPORTED;
            }
            return ours ? ENABLED : NOT_ENABLED;
        }
    }

    /** 設定段落的主要按鈕。 */
    enum Action {
        /** 不顯示。 */
        NONE,
        /** 「設定自動填入」：開引導頁。 */
        OPEN_GUIDE,
        /** 「前往系統設定」：直接開系統的自動填入服務選單。 */
        OPEN_SYSTEM_SETTINGS
    }

    /**
     * 首次解鎖後要不要自動開引導（PRD US-10「首次啟用引導畫面」；外殼的解鎖後提示，T-105）：模組啟用、系統支援但還沒選
     * S-Vault、引導頁還沒看過。看過一次（引導頁會記下）之後只從設定段落進入。
     *
     * @param enabled 模組啟用
     * @return 要開
     */
    boolean offerAfterUnlock(boolean enabled) {
        return enabled && primary() == Action.OPEN_GUIDE;
    }

    /** @return 設定段落的主要按鈕 */
    Action primary() {
        if (service != Service.NOT_ENABLED) {
            return Action.NONE;
        }
        return guideSeen ? Action.OPEN_SYSTEM_SETTINGS : Action.OPEN_GUIDE;
    }

    /** @return 設定段落另列「自動填入說明」（主要按鈕已經是引導、或裝置不支援時不列） */
    boolean showGuideLink() {
        return service != Service.UNSUPPORTED && primary() != Action.OPEN_GUIDE;
    }

    /**
     * @param service 系統目前的狀態
     * @return 引導頁顯示「前往系統設定」（已啟用時系統的選單會直接結束，所以不顯示）
     */
    static boolean guideOffersSystemSettings(Service service) {
        return service == Service.NOT_ENABLED;
    }
}
