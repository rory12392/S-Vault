package app.svault.fixture.feature.biometric;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;

/** 合法範例：feature.biometric 用 Keystore 的 AES-GCM（{@code ArchRules.cryptoOnlyInVault} 的例外）。 */
public final class KeystoreWrap {

    private KeystoreWrap() {
    }

    /**
     * @return 包主密碼用的加密器
     * @throws GeneralSecurityException 不會發生（範例）
     */
    public static Cipher wrapper() throws GeneralSecurityException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }
}
