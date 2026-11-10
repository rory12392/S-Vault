package app.svault.fixture.core;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;

/** 違規範例：core.vault 以外直接用 javax.crypto（{@code ArchRules.cryptoOnlyInVault} 要擋下）。 */
public final class UsesCipher {

    private UsesCipher() {
    }

    /**
     * @return 自己組的加密器
     * @throws GeneralSecurityException 不會發生（範例）
     */
    public static Cipher homemade() throws GeneralSecurityException {
        return Cipher.getInstance("AES/GCM/NoPadding");
    }
}
