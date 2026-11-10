package app.svault.android.feature.biometric;

/**
 * 生物辨識解鎖的預期內失敗，帶 {@link BioReason}。訊息只含原因與例外類型，不含任何機密。
 */
public final class BioException extends Exception {

    private static final long serialVersionUID = 1L;

    private final BioReason reason;

    /**
     * @param reason 原因
     */
    public BioException(BioReason reason) {
        super(reason.name());
        this.reason = reason;
    }

    /**
     * @param reason 原因
     * @param cause  底層例外（Keystore 等；訊息不含機密）
     */
    public BioException(BioReason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    /** @return 原因 */
    public BioReason reason() {
        return reason;
    }
}
