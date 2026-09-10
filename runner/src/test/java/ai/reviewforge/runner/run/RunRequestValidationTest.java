package ai.reviewforge.runner.run;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RunRequestValidationTest {

    private final Validator validator = factory().getValidator();

    @Test
    void acceptsARelativeTestPathAndPlainSelector() {
        assertThat(validator.validate(request(
                "src/test/java/com/acme/OrderReviewForgeTest.java", "com.acme.OrderReviewForgeTest"))).isEmpty();
    }

    @Test
    void refusesPathsOutsideTheTestTree() {
        assertThat(validator.validate(request("src/main/java/com/acme/Order.java", "com.acme.OrderTest")))
                .isNotEmpty();
        assertThat(validator.validate(request("/etc/passwd", "com.acme.OrderTest"))).isNotEmpty();
        assertThat(validator.validate(request("src/test/../../escape/Test.java", "com.acme.OrderTest")))
                .isNotEmpty();
    }

    @Test
    void refusesSelectorsThatCouldCarryShellOrMavenArguments() {
        assertThat(validator.validate(request(
                "src/test/java/A.java", "com.acme.OrderTest -Dmaven.repo.local=/tmp"))).isNotEmpty();
        assertThat(validator.validate(request("src/test/java/A.java", "a; rm -rf /"))).isNotEmpty();
    }

    @Test
    void refusesNonPositiveTimeouts() {
        RunRequest request = new RunRequest(
                "src/test/java/A.java", "class A {}", "MAVEN_SINGLE_TEST", "A", 0
        );

        assertThat(validator.validate(request)).isNotEmpty();
    }

    private RunRequest request(String patchPath, String selector) {
        return new RunRequest(patchPath, "class A {}", "MAVEN_SINGLE_TEST", selector, 60);
    }

    private ValidatorFactory factory() {
        return Validation.buildDefaultValidatorFactory();
    }
}
