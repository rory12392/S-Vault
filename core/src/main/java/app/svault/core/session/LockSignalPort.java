package app.svault.core.session;

/**
 * 平台提供的「現在就要上鎖」訊號（CONVENTIONS §2 的埠介面；T-18）：Windows 鎖定螢幕（AC-12-1）、Android 螢幕關閉
 * （AC-12-2）。閒置與切到背景是計時，由平台外殼搭配 {@link IdleTimer} 處理，不經過這裡。
 */
public interface LockSignalPort {

    /** 不提供任何訊號（不支援的平台、測試）。 */
    LockSignalPort NONE = new LockSignalPort() {
        @Override
        public boolean start(Runnable onSignal) {
            return false;
        }

        @Override
        public void stop() {
        }
    };

    /**
     * 開始監看。訊號可能在任何執行緒送達，{@code onSignal} 自己轉到 UI 執行緒。
     *
     * @param onSignal 收到訊號時呼叫
     * @return 已開始監看；平台不支援或註冊失敗時 {@code false}（只記錄類型，程式照常運作，閒置上鎖仍有效）
     */
    boolean start(Runnable onSignal);

    /** 停止監看；沒有開始過時不做事。 */
    void stop();
}
