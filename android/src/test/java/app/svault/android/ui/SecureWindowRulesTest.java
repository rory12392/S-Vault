package app.svault.android.ui;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.Assert.assertFalse;

import app.svault.core.arch.ArchRules;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Set;
import org.junit.Test;

/**
 * 對話框是另一個視窗，不繼承 Activity 的 {@code FLAG_SECURE}（AC-12-4，T-19）：每個顯示對話框的類別都要經過
 * {@link SecureWindows}。規則本身會不會擋，由 {@link #rulesCatchViolations()} 以違規範例確認。
 */
public class SecureWindowRulesTest {

    private static final JavaClasses MAIN = ArchRules.importMain("app.svault.android");
    private static final Set<String> BUILDERS = Set.of("androidx.appcompat.app.AlertDialog$Builder",
            "com.google.android.material.dialog.MaterialAlertDialogBuilder", "android.app.AlertDialog$Builder");

    /** {@code Builder.show()} 在回傳前就顯示了，來不及設定：一律先 {@code create()}，經 SecureWindows 再顯示。 */
    static final ArchRule NO_BUILDER_SHOW = noClasses().should().callMethodWhere(
            new DescribedPredicate<JavaMethodCall>("AlertDialog.Builder#show()") {
                @Override
                public boolean test(JavaMethodCall call) {
                    return call.getName().equals("show") && isBuilder(call.getTargetOwner());
                }
            });

    /** 顯示對話框（{@code Dialog#show()}）或寫 {@code DialogFragment} 的類別要呼叫 SecureWindows。 */
    static final ArchRule DIALOGS_GO_THROUGH_SECURE_WINDOWS = classes().that(
            new DescribedPredicate<JavaClass>("顯示對話框的類別") {
                @Override
                public boolean test(JavaClass c) {
                    return c.isAssignableTo("androidx.fragment.app.DialogFragment") || c.getMethodCallsFromSelf()
                            .stream().anyMatch(call -> call.getName().equals("show")
                                    && call.getTargetOwner().isAssignableTo("android.app.Dialog"));
                }
            }).and().doNotHaveFullyQualifiedName(SecureWindows.class.getName())
            .should(new ArchCondition<JavaClass>("呼叫 SecureWindows") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    boolean ok = c.getMethodCallsFromSelf().stream()
                            .anyMatch(call -> call.getTargetOwner().getName().equals(SecureWindows.class.getName()));
                    events.add(new SimpleConditionEvent(c, ok, c.getName() + " 顯示對話框但沒有經過 SecureWindows"));
                }
            }).allowEmptyShould(true);

    @Test
    public void dialogsAreSecured() {
        NO_BUILDER_SHOW.check(MAIN);
        DIALOGS_GO_THROUGH_SECURE_WINDOWS.check(MAIN);
    }

    @Test
    public void rulesCatchViolations() {
        JavaClasses bad = new ClassFileImporter().importClasses(BadDialogs.class);
        assertFalse(NO_BUILDER_SHOW.evaluate(bad).getFailureReport().isEmpty());
        assertFalse(DIALOGS_GO_THROUGH_SECURE_WINDOWS.evaluate(bad).getFailureReport().isEmpty());
    }

    private static boolean isBuilder(JavaClass owner) {
        if (BUILDERS.contains(owner.getName())) {
            return true;
        }
        for (String b : BUILDERS) {
            if (owner.isAssignableTo(b)) {
                return true;
            }
        }
        return false;
    }

    /** 違規範例：直接 {@code builder.show()}。 */
    static final class BadDialogs {
        @SuppressWarnings("unused")
        void show(android.content.Context context) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(context).setTitle("x").show();
            new androidx.appcompat.app.AlertDialog.Builder(context).create().show();
        }
    }
}
