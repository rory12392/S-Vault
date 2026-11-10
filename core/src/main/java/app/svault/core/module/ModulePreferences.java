package app.svault.core.module;

import java.util.Optional;

/**
 * 一個功能模組自己的設定（{@code settings.json} 的 {@code modules.<id>.*}，Architecture §2.2；T-22）。
 * 鍵是去掉 {@code modules.<id>.} 前綴的相對鍵，例如 GEN 的 {@code length} 存成 {@code modules.generator.length}；
 * 模組只看得到、也只改得到自己的段落，核心的設定（主題、自動上鎖、剪貼簿秒數）與其他模組的段落都碰不到。
 *
 * <p>改了立即生效、寫檔由平台放到背景執行緒（同設定畫面）。不得存放密碼、條目內容等機密：
 * {@code settings.json} 不加密。
 */
public interface ModulePreferences {

    /**
     * @param key 相對鍵（點分隔，例如 {@code lastCheck} 或 {@code options.length}）
     * @return 字串值；沒有設定或型別不同時為空
     * @throws IllegalArgumentException 鍵是空的或格式不對
     */
    Optional<String> string(String key);

    /**
     * @param key 相對鍵
     * @return 整數值；沒有設定或型別不同時為空
     * @throws IllegalArgumentException 鍵是空的或格式不對
     */
    Optional<Long> number(String key);

    /**
     * @param key 相對鍵
     * @return 布林值；沒有設定或型別不同時為空
     * @throws IllegalArgumentException 鍵是空的或格式不對
     */
    Optional<Boolean> bool(String key);

    /**
     * 改設定（立即生效並排定寫檔）。在 UI 執行緒呼叫。
     *
     * @param key   相對鍵
     * @param value 字串、{@link Long}、{@link Boolean}；{@code null} 表示移除
     * @throws IllegalArgumentException 鍵格式不對或值的型別不支援
     */
    void put(String key, Object value);
}
