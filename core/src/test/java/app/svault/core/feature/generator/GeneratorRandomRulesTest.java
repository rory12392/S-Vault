package app.svault.core.feature.generator;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Random;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AC-04-3：產生器只用密碼學安全的亂數。{@link PasswordGenerator} 的建構子型別已限定 {@code SecureRandom}；
 * 這裡再擋住之後有人在模組裡改用一般的亂數（{@code new Random()}、{@code Math.random()} 等）。
 */
class GeneratorRandomRulesTest {

    private static final JavaClasses GENERATOR = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("app.svault.core.feature.generator");

    @Test
    @DisplayName("AC-04-3：feature.generator 不建立 java.util.Random、不用 ThreadLocalRandom／SplittableRandom／Math.random")
    void ac04_3_noInsecureRandom() {
        noClasses().should().callConstructor(Random.class)
                .orShould().callConstructor(Random.class, long.class)
                .orShould().dependOnClassesThat().belongToAnyOf(ThreadLocalRandom.class, SplittableRandom.class)
                .orShould().callMethod(Math.class, "random")
                .orShould().callMethod(StrictMath.class, "random")
                .as("產生器只用 SecureRandom（AC-04-3）")
                .check(GENERATOR);
    }
}
