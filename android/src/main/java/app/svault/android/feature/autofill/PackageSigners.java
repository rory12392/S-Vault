package app.svault.android.feature.autofill;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 用 {@code PackageManager} 查簽署憑證（C-1）。有輪替過的 App 取整串歷史（任一把在清單裡就算），多個簽署者時取全部。
 * 瀏覽器的可見性由 manifest 的 {@code <queries>}（處理 https 網址的 App）提供。
 */
final class PackageSigners implements Signers {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final PackageManager pm;

    PackageSigners(Context context) {
        this.pm = context.getPackageManager();
    }

    @Override
    public Set<String> of(String packageName) {
        Set<String> out = new HashSet<>();
        try {
            PackageInfo info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES);
            SigningInfo signing = info.signingInfo;
            if (signing == null) {
                return out;
            }
            Signature[] sigs = signing.hasMultipleSigners() ? signing.getApkContentsSigners()
                    : signing.getSigningCertificateHistory();
            for (Signature s : sigs) {
                out.add(sha256(s.toByteArray()));
            }
        } catch (PackageManager.NameNotFoundException e) {
            // 沒有安裝或看不到：當成沒有憑證，網域不採信
        }
        return out;
    }

    private static String sha256(byte[] cert) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(cert);
            char[] out = new char[d.length * 2];
            for (int i = 0; i < d.length; i++) {
                out[i * 2] = HEX[(d[i] >> 4) & 0xf];
                out[i * 2 + 1] = HEX[d[i] & 0xf];
            }
            return new String(out).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
