package app.svault.core.module;

import java.time.Duration;

/**
 * 外殼給功能模組的剪貼簿（T-34；第一個使用者是 GEN 的產生器頁「複製」）。兩平台都經 {@code ModuleHost} 取得，背後是外殼
 * 複製密碼用的同一個 {@code ClipboardGuard}：清除秒數、上鎖時立即清、android 切到背景上鎖的例外、android 的敏感內容標記、
 * Windows 不進剪貼簿歷程記錄，全部和條目畫面的「複製密碼」相同。
 *
 * <p>只能放進剪貼簿：讀不到剪貼簿的內容，也不能改清除的秒數（秒數只由設定畫面決定，AC-05-1）。外殼不記錄複製的值。
 *
 * <p><b>只在 UI 執行緒呼叫</b>（desktop 的 JavaFX 執行緒、android 的主執行緒），其他執行緒丟 {@link IllegalStateException}、
 * 不放進剪貼簿。
 */
public interface ModuleClipboard {

    /**
     * 複製密碼：標成敏感內容，設定的秒數後清除（AC-05-1～AC-05-3）；上鎖時立即清除（AC-05-4）。再由 S-Vault 複製別的
     * 內容時不再清除（密碼已經不在剪貼簿上）。
     *
     * @param secret 密碼（不可為 {@code null}）
     * @return 多久後清除；畫面的「已複製密碼，n 秒後清除」用這個值（{@code getSeconds()}），不要自己讀設定
     * @throws IllegalStateException 不在 UI 執行緒
     */
    Duration copySecret(String secret);

    /**
     * 複製一般文字（不清除、不標成敏感）。剪貼簿上原本由 S-Vault 複製的密碼已被取代，取消它的清除。
     *
     * @param text 內容（不可為 {@code null}）
     * @throws IllegalStateException 不在 UI 執行緒
     */
    void copy(String text);
}
