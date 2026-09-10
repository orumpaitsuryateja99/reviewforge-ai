package ai.reviewforge.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ContextBuilderTest {

    private static final List<String> TREE = List.of(
            "pom.xml",
            "src/main/java/com/acme/OrderService.java",
            "src/test/java/com/acme/OrderServiceTest.java",
            "src/test/java/com/acme/PaymentIT.java"
    );

    @Test
    void findsTheConventionalTestForASourceFile() {
        assertThat(ContextBuilder.findExistingTest(TREE, "src/main/java/com/acme/OrderService.java"))
                .isEqualTo("src/test/java/com/acme/OrderServiceTest.java");
    }

    @Test
    void findsIntegrationTestNamingToo() {
        assertThat(ContextBuilder.findExistingTest(TREE, "src/main/java/com/acme/Payment.java"))
                .isEqualTo("src/test/java/com/acme/PaymentIT.java");
    }

    @Test
    void returnsNothingWhenNoTestFollowsTheConvention() {
        assertThat(ContextBuilder.findExistingTest(TREE, "src/main/java/com/acme/Ledger.java")).isNull();
        assertThat(ContextBuilder.findExistingTest(TREE, "Makefile")).isNull();
    }

    @Test
    void findsTheBuildFileAtTheRepositoryRoot() {
        assertThat(ContextBuilder.findBuildFile(TREE)).isEqualTo("pom.xml");
        assertThat(ContextBuilder.findBuildFile(List.of("src/main/java/A.java"))).isNull();
    }
}
