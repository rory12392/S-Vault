package app.svault.android.feature.autofill;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 一般 App 的簽署憑證紀錄（I-5）：{@code androidapp://} 條目只看套件名稱，所以第一次把帳密交給某個 App 時記下它的
 * 憑證，之後同一個套件名稱換了憑證（原 App 被移除、換成冒用名稱的 App）就不再自動交出，手動挑時要先警告。
 *
 * <p>擋不住「第一次就是冒牌貨」（那時還沒有紀錄）；那種情況要使用者自己側載了冒牌 App。設定檔不加密，所以只存
 * 套件名稱與憑證的雜湊（看不出用過哪些 App）。
 */
final class AppSignerPins {

    /** 讀寫模組設定（{@code modules.autofill.*}）。 */
    interface Store {

        /**
         * @param key 相對鍵
         * @return 值
         */
        Optional<String> get(String key);

        /**
         * @param key   相對鍵
         * @param value 值
         */
        void put(String key, String value);
    }

    /** 不記錄也不檢查（只有測試與還沒有 Context 的地方用）。 */
    static final AppSignerPins OFF = new AppSignerPins(null);

    private final Store store;

    AppSignerPins(Store store) {
        this.store = store;
    }

    /**
     * 沒有紀錄時記下目前的憑證並放行；有紀錄時目前的憑證要包含記下的那一把。
     *
     * @param packageName 請求來源的套件名稱
     * @param current     目前的簽署憑證（{@link Signers#of}）
     * @return 可以照套件名稱交出帳密
     */
    boolean trusted(String packageName, Set<String> current) {
        if (store == null) {
            return true;
        }
        if (current.isEmpty()) {
            // 查不到憑證（沒安裝、看不到）：無從確認是誰，不自動交出，也不留紀錄（Codex M-04 審查 B1）
            return false;
        }
        String key = "pins." + hash(packageName).substring(0, 16);
        Optional<String> pinned = store.get(key);
        if (pinned.isEmpty()) {
            // 多把憑證時記排序後的第一把：之後只要它還在就算同一個發佈者
            store.put(key, hash(new TreeSet<>(current).first()));
            return true;
        }
        for (String c : current) {
            if (hash(c).equals(pinned.get())) {
                return true;
            }
        }
        return false;
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(d.length * 2);
            for (byte b : d) {
                out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
