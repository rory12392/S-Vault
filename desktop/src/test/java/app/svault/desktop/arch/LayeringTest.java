package app.svault.desktop.arch;

import app.svault.core.arch.ArchRules;
import com.tngtech.archunit.core.domain.JavaClasses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * desktop 的模組邊界（Architecture §1.1）。規則本身會不會擋，由 core 的 LayeringTest 以違規範例驗證。
 */
class LayeringTest {

    private static final String ROOT = "app.svault.desktop";
    private static final JavaClasses MAIN = ArchRules.importMain(ROOT);

    @Test
    @DisplayName("功能模組彼此獨立，只依賴核心、擴充點、共用元件與 JavaFX")
    void featureBoundaries() {
        ArchRules.featuresAreIndependent(ROOT).check(MAIN);
        ArchRules.featuresOnlyUseCoreAndSelf(ROOT, "javafx..").check(MAIN);
    }

    @Test
    @DisplayName("只有 core.vault 使用 KeePassJava2；只有 feature.health 連網")
    void libraryAndNetwork() {
        ArchRules.onlyVaultUsesKeePassJava2().check(MAIN);
        ArchRules.onlyHealthUsesNetworkClasses().check(MAIN);
        ArchRules.onlyHealthOpensUrls().check(MAIN);
    }

    @Test
    @DisplayName("AC-15-2：desktop 不直接用加密原語（只在 core.vault）")
    void ac15_2_cryptoOnlyInVault() {
        ArchRules.cryptoOnlyInVault().check(MAIN);
    }
}
