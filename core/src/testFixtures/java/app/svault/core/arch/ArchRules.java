package app.svault.core.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import java.net.DatagramSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 三個子專案的 {@code LayeringTest} 共用的模組邊界規則（Architecture §1.1、CONVENTIONS §2、§11）。
 *
 * <p>放在 testFixtures 而不是各自複製，讓三邊的規則永遠一致。ArchUnit 只在編譯期可見，使用端自己加相依。
 * 規則都允許「沒有符合的類別」：功能模組在 M-03 才有程式。
 */
public final class ArchRules {

    /** core 的根套件。 */
    public static final String CORE = "app.svault.core";

    private ArchRules() {
    }

    /**
     * 只匯入正式程式（排除 Gradle 與 AGP 的測試輸出），讓規則不被測試用的違規範例觸發。
     *
     * @param packages 要匯入的套件
     * @return 正式程式的類別
     */
    public static JavaClasses importMain(String... packages) {
        ImportOption mainOnly = location -> !location.contains("/test/")
                && !location.contains("UnitTest")
                && !location.contains("AndroidTest")
                && !location.contains("testFixtures")
                && !location.contains("test-fixtures");
        return new ClassFileImporter().withImportOption(mainOnly).importPackages(packages);
    }

    /**
     * 功能模組彼此不 import。
     *
     * @param root 子專案的根套件，例如 {@code app.svault.desktop}
     * @return 規則
     */
    public static ArchRule featuresAreIndependent(String root) {
        return slices().matching(root + ".feature.(*)..")
                .should().notDependOnEachOther()
                .allowEmptyShould(true)
                .as("功能模組彼此不依賴（" + root + ".feature.*）");
    }

    /**
     * 功能模組只依賴 JDK、核心、平台的擴充點與共用元件、自己（不碰外殼內部與平台實作）。
     *
     * @param root          子專案的根套件
     * @param extraPackages 平台框架的套件，例如 {@code javafx..}、{@code android..}
     * @return 規則
     */
    public static ArchRule featuresOnlyUseCoreAndSelf(String root, String... extraPackages) {
        List<String> allowed = new ArrayList<>(Arrays.asList(
                "java..", "javax..", "", CORE + "..",
                root + ".feature..", root + ".shell.ext..", root + ".ui.."));
        allowed.addAll(Arrays.asList(extraPackages));
        return classes().that().resideInAPackage(root + ".feature..")
                .should().onlyDependOnClassesThat().resideInAnyPackage(allowed.toArray(new String[0]))
                .allowEmptyShould(true)
                .as("功能模組只依賴核心、擴充點、共用元件與自己（" + root + "）");
    }

    /**
     * 只有 {@code app.svault.core.vault} 能使用 KeePassJava2。
     *
     * @return 規則
     */
    public static ArchRule onlyVaultUsesKeePassJava2() {
        return noClasses().that().resideOutsideOfPackage(CORE + ".vault..")
                .should().dependOnClassesThat().resideInAPackage("org.linguafranca..")
                .allowEmptyShould(true)
                .as("只有 core.vault 能使用 KeePassJava2");
    }

    /**
     * 加密原語只在 {@code app.svault.core.vault}（AC-15-2）：{@code javax.crypto}、
     * {@code java.security.spec}、Bouncy Castle 只能在那裡用（實際的加解密都交給 KeePassJava2，這裡只有
     * {@code VaultRuntimeCheck} 確認 Argon2 載入得了）。例外是 {@code feature.biometric}：M-03 要用 Android Keystore
     * 的 AES-GCM 包住主密碼（AC-11-2），只能經 {@code javax.crypto.Cipher}；先寫進規則，模組分支不必改 core。
     *
     * <p>{@code MessageDigest}（SHA-256 雜湊、HLT 的 SHA-1 範圍查詢）與 {@code SecureRandom}（產生器）是 JDK 內建、
     * 不是自己實作的演算法，不在限制內。這條規則擋的是「在 vault 以外拼加密流程」；「沒有自己實作演算法」另靠
     * 這條規則加上程式審查。
     *
     * @return 規則
     */
    public static ArchRule cryptoOnlyInVault() {
        return noClasses().that().resideOutsideOfPackages(CORE + ".vault..", "..feature.biometric..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "javax.crypto..", "java.security.spec..", "org.bouncycastle..")
                .allowEmptyShould(true)
                .as("加密原語（javax.crypto、java.security.spec、Bouncy Castle）只在 core.vault（與 feature.biometric）");
    }

    /**
     * 只有 {@code feature.health} 能使用連網 API。{@code java.net.URI}、{@code java.net.URL} 的解析可以用。
     *
     * @return 規則
     */
    public static ArchRule onlyHealthUsesNetworkClasses() {
        DescribedPredicate<JavaClass> network = JavaClass.Predicates.assignableTo(URLConnection.class)
                .or(JavaClass.Predicates.assignableTo(Socket.class))
                .or(JavaClass.Predicates.assignableTo(ServerSocket.class))
                .or(JavaClass.Predicates.assignableTo(DatagramSocket.class))
                .or(JavaClass.Predicates.resideInAPackage("java.net.http.."));
        return noClasses().that().resideOutsideOfPackage("..feature.health..")
                .should().dependOnClassesThat(network.as("連網類別（URLConnection、Socket、java.net.http）"))
                .allowEmptyShould(true)
                .as("只有 feature.health 能使用連網類別");
    }

    /**
     * 只有 {@code feature.health} 能呼叫 {@code URL#openConnection}、{@code URL#openStream}。
     *
     * @return 規則
     */
    public static ArchRule onlyHealthOpensUrls() {
        DescribedPredicate<JavaMethodCall> opensUrl =
                new DescribedPredicate<JavaMethodCall>("呼叫 URL.openConnection／openStream") {
                    @Override
                    public boolean test(JavaMethodCall call) {
                        String name = call.getName();
                        return call.getTargetOwner().isAssignableTo(URL.class)
                                && (name.equals("openConnection") || name.equals("openStream"));
                    }
                };
        return noClasses().that().resideOutsideOfPackage("..feature.health..")
                .should().callMethodWhere(opensUrl)
                .allowEmptyShould(true)
                .as("只有 feature.health 能開啟網路連線");
    }

    /**
     * core 不依賴任何平台 API（CONVENTIONS §2）。
     *
     * @param corePackage core 的根套件（測試違規範例時可換成別的套件）
     * @return 規則
     */
    public static ArchRule coreHasNoPlatformApi(String corePackage) {
        return noClasses().that().resideInAPackage(corePackage + "..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "javafx..", "android..", "androidx..", "java.awt..", "javax.swing..")
                .allowEmptyShould(true)
                .as("core 不依賴平台 API（" + corePackage + "）");
    }
}
