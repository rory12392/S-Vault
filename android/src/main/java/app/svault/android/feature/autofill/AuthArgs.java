package app.svault.android.feature.autofill;

import android.os.Build;
import android.os.Bundle;
import android.view.autofill.AutofillId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 驗證項目帶給驗證頁（{@link AfAuthFragment}）的參數：請求來源的套件名稱、網域與欄位代號。解鎖後用它們重新比對，
 * 不用系統另外附上的 {@code AssistStructure}（規格：不保存畫面內容）；這裡也沒有任何欄位的內容或條目資料。
 *
 * <p>T-110（D-197）起每筆建議也經驗證頁：{@code entryId} 是那一筆條目的 UUID（只有代號、沒有內容），驗證頁確認仍解鎖、
 * 仍符合後只交回那一筆。
 *
 * @param packageName 請求來源的套件名稱
 * @param fields      要填的欄位與網域
 * @param entryId     單筆建議的條目 UUID；整份回應的驗證時為 {@code null}
 */
record AuthArgs(String packageName, FieldScan<AutofillId> fields, String entryId) {

    private static final String KEY_PACKAGE = "af.package";
    private static final String KEY_DOMAIN = "af.domain";
    private static final String KEY_SCHEME = "af.scheme";
    private static final String KEY_USERNAMES = "af.usernames";
    private static final String KEY_PASSWORDS = "af.passwords";
    private static final String KEY_ENTRY = "af.entry";

    /**
     * @param packageName 請求來源的套件名稱
     * @param fields      要填的欄位與網域
     */
    AuthArgs(String packageName, FieldScan<AutofillId> fields) {
        this(packageName, fields, null);
    }

    /**
     * @param uuid 條目 UUID
     * @return 同一次請求、只填這一筆的參數
     */
    AuthArgs forEntry(String uuid) {
        return new AuthArgs(packageName, fields, uuid);
    }

    /** @return 放進 {@code PendingIntent} 的參數 */
    Bundle toBundle() {
        Bundle b = new Bundle();
        b.putString(KEY_PACKAGE, packageName);
        b.putString(KEY_DOMAIN, fields.webDomain());
        b.putString(KEY_SCHEME, fields.webScheme());
        b.putParcelableArrayList(KEY_USERNAMES, new ArrayList<>(fields.usernames()));
        b.putParcelableArrayList(KEY_PASSWORDS, new ArrayList<>(fields.passwords()));
        // 沒有條目時也放一個值：FLAG_MUTABLE 的 fillIn 蓋不掉已有的鍵，送出端就不能自己加條目代號
        b.putString(KEY_ENTRY, entryId == null ? "" : entryId);
        return b;
    }

    /**
     * @param b 驗證頁收到的參數
     * @return 參數；沒有或不完整（例如不是由本模組開的）時為空
     */
    static Optional<AuthArgs> from(Bundle b) {
        if (b == null) {
            return Optional.empty();
        }
        String pkg = b.getString(KEY_PACKAGE);
        List<AutofillId> usernames = ids(b, KEY_USERNAMES);
        List<AutofillId> passwords = ids(b, KEY_PASSWORDS);
        if (pkg == null || usernames == null || passwords == null) {
            return Optional.empty();
        }
        String entry = b.getString(KEY_ENTRY);
        return Optional.of(new AuthArgs(pkg, new FieldScan<>(usernames, passwords, b.getString(KEY_DOMAIN),
                b.getString(KEY_SCHEME)), entry == null || entry.isEmpty() ? null : entry));
    }

    private static List<AutofillId> ids(Bundle b, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return b.getParcelableArrayList(key, AutofillId.class);
        }
        return legacyIds(b, key);
    }

    @SuppressWarnings("deprecation")
    private static List<AutofillId> legacyIds(Bundle b, String key) {
        return b.getParcelableArrayList(key);
    }
}
