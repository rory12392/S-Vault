package app.svault.android.arch;

import app.svault.core.arch.ArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.Test;

/**
 * Android 的模組邊界（Architecture §1.1）。規則本身會不會擋，由 core 的 LayeringTest 以違規範例驗證。
 */
public class LayeringTest {

    private static final String ROOT = "app.svault.android";
    private static final JavaClasses MAIN = ArchRules.importMain(ROOT);

    @Test
    public void featureBoundaries() {
        ArchRules.featuresAreIndependent(ROOT).check(MAIN);
        ArchRules.featuresOnlyUseCoreAndSelf(ROOT,
                "android..", "androidx..", "com.google.android.material..", ROOT).check(MAIN);
    }

    @Test
    public void libraryAndNetwork() {
        ArchRules.onlyVaultUsesKeePassJava2().check(MAIN);
        ArchRules.onlyHealthUsesNetworkClasses().check(MAIN);
        ArchRules.onlyHealthOpensUrls().check(MAIN);
    }

    /** AC-15-2：android 不直接用加密原語（只在 core.vault；feature.biometric 的 Keystore 例外）。 */
    @Test
    public void ac15_2_cryptoOnlyInVault() {
        ArchRules.cryptoOnlyInVault().check(MAIN);
    }
}
