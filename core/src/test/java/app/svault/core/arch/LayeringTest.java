package app.svault.core.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * core 的模組邊界（Architecture §1.1）。先確認正式程式符合規則，再用 {@code app.svault.fixture} 的違規範例
 * 確認每條規則真的會擋（規則寫錯時會「永遠通過」，所以兩邊都要測）。
 */
class LayeringTest {

    private static final String FIXTURE = "app.svault.fixture";

    private static final JavaClasses MAIN = ArchRules.importMain(ArchRules.CORE);
    private static final JavaClasses FIXTURES = new ClassFileImporter().importPackages(FIXTURE);

    @Test
    @DisplayName("正式程式：功能模組彼此獨立、只依賴核心與自己")
    void featureBoundaries() {
        ArchRules.featuresAreIndependent(ArchRules.CORE).check(MAIN);
        ArchRules.featuresOnlyUseCoreAndSelf(ArchRules.CORE).check(MAIN);
    }

    @Test
    @DisplayName("正式程式：只有 core.vault 使用 KeePassJava2")
    void keePassJava2OnlyInVault() {
        ArchRules.onlyVaultUsesKeePassJava2().check(MAIN);
    }

    @Test
    @DisplayName("AC-15-2：正式程式的加密原語只在 core.vault")
    void ac15_2_cryptoOnlyInVault() {
        ArchRules.cryptoOnlyInVault().check(MAIN);
    }

    @Test
    @DisplayName("AC-15-2 違規範例：core.vault 以外用 javax.crypto 會被擋；feature.biometric 的 Keystore 不會")
    void ac15_2_catchesCryptoOutsideVault() {
        EvaluationResult result = ArchRules.cryptoOnlyInVault().evaluate(FIXTURES);
        assertTrue(result.hasViolation(), "規則沒有擋下違規範例");
        String report = String.join("\n", result.getFailureReport().getDetails());
        assertTrue(report.contains("UsesCipher"), report);
        assertFalse(report.contains("KeystoreWrap"), report);
    }

    @Test
    @DisplayName("正式程式：只有 feature.health 連網")
    void networkOnlyInHealth() {
        ArchRules.onlyHealthUsesNetworkClasses().check(MAIN);
        ArchRules.onlyHealthOpensUrls().check(MAIN);
    }

    @Test
    @DisplayName("正式程式：core 不依賴平台 API")
    void noPlatformApi() {
        ArchRules.coreHasNoPlatformApi(ArchRules.CORE).check(MAIN);
    }

    @Test
    @DisplayName("違規範例：功能模組互相 import 會被擋")
    void catchesCrossFeatureDependency() {
        assertViolation(ArchRules.featuresAreIndependent(FIXTURE), "BetaService");
    }

    @Test
    @DisplayName("違規範例：功能模組依賴核心以外的函式庫會被擋，只用 JDK 的不會")
    void catchesForeignDependency() {
        EvaluationResult result = ArchRules.featuresOnlyUseCoreAndSelf(FIXTURE).evaluate(FIXTURES);
        assertTrue(result.hasViolation());
        String report = String.join("\n", result.getFailureReport().getDetails());
        assertTrue(report.contains("AlphaViolations"), report);
        assertFalse(report.contains("BetaService"), report);
    }

    @Test
    @DisplayName("違規範例：core.vault 以外使用 KeePassJava2 會被擋")
    void catchesKeePassJava2OutsideVault() {
        assertViolation(ArchRules.onlyVaultUsesKeePassJava2(), "JacksonDatabase");
    }

    @Test
    @DisplayName("違規範例：health 以外用 Socket、開 URL 會被擋；health 本身與 URI 不會")
    void catchesNetworkOutsideHealth() {
        EvaluationResult classesResult = ArchRules.onlyHealthUsesNetworkClasses().evaluate(FIXTURES);
        String classesReport = String.join("\n", classesResult.getFailureReport().getDetails());
        assertTrue(classesReport.contains("java.net.Socket"), classesReport);
        assertFalse(classesReport.contains("HealthClient"), classesReport);
        assertFalse(classesReport.contains("java.net.URI"), classesReport);

        EvaluationResult callsResult = ArchRules.onlyHealthOpensUrls().evaluate(FIXTURES);
        String callsReport = String.join("\n", callsResult.getFailureReport().getDetails());
        assertTrue(callsReport.contains("openStream"), callsReport);
        assertFalse(callsReport.contains("HealthClient"), callsReport);
    }

    @Test
    @DisplayName("違規範例：core 使用 java.awt 會被擋")
    void catchesPlatformApi() {
        assertViolation(ArchRules.coreHasNoPlatformApi(FIXTURE + ".core"), "java.awt.Point");
    }

    private static void assertViolation(ArchRule rule, String expected) {
        EvaluationResult result = rule.evaluate(FIXTURES);
        assertTrue(result.hasViolation(), "規則沒有擋下違規範例：" + rule.getDescription());
        String report = String.join("\n", result.getFailureReport().getDetails());
        assertTrue(report.contains(expected), report);
    }
}
