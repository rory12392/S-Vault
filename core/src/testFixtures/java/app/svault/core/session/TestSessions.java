package app.svault.core.session;

import app.svault.core.storage.LocalStore;
import app.svault.core.storage.RecentStore;
import app.svault.core.storage.SaveCoordinator;
import app.svault.core.vault.VaultCodec;
import java.time.Clock;

/**
 * 測試用的 {@link VaultSession} 組裝：最近開啟清單、存檔協調器與 session 都用同一個本機資料夾與時鐘
 * （T-13 從 7 個測試檔的重複整理出來；不依賴任何測試框架）。
 */
public final class TestSessions {

    /**
     * 組裝好的三個物件。
     *
     * @param recent  {@code store.recentFile()} 的最近開啟清單
     * @param saver   存檔協調器
     * @param session 解鎖狀態
     */
    public record Parts(RecentStore recent, SaveCoordinator saver, VaultSession session) {
    }

    private TestSessions() {
    }

    /**
     * @param codec 開檔與序列化（測試通常用 {@code TestVaults.fastParams()}）
     * @param store 本機資料夾
     * @param clock 時鐘
     * @return 共用 {@code store} 與 {@code clock} 的最近開啟清單、存檔協調器與 session
     */
    public static Parts assemble(VaultCodec codec, LocalStore store, Clock clock) {
        RecentStore recent = new RecentStore(store.recentFile());
        SaveCoordinator saver = new SaveCoordinator(codec, store, recent, clock);
        return new Parts(recent, saver, new VaultSession(codec, saver, recent, clock));
    }
}
