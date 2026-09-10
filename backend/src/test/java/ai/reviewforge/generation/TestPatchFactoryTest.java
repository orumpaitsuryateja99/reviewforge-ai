package ai.reviewforge.generation;

import ai.reviewforge.common.api.ApiException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPatchFactoryTest {

    private static final String SOURCE = """
            package com.acme;

            import org.junit.jupiter.api.Test;

            class OrderServiceReviewForgeTest {
                @Test
                void oversellsUnderConcurrentReservations() {
                }
            }
            """;

    private final TestPatchFactory factory = new TestPatchFactory();

    @Test
    void mirrorsTheMainSourceLayoutIntoTheTestTree() {
        String path = factory.targetPath(
                "backend/src/main/java/com/acme/OrderService.java", "OrderServiceReviewForgeTest");

        assertThat(path).isEqualTo("backend/src/test/java/com/acme/OrderServiceReviewForgeTest.java");
    }

    @Test
    void fallsBackToTheStandardTestRootForUnconventionalSources() {
        assertThat(factory.targetPath("lib/Order.java", "OrderTest"))
                .isEqualTo("src/test/java/OrderTest.java");
    }

    @Test
    void avoidsCollidingWithAnExistingPath() {
        String path = factory.uniquePath("src/test/java/com/acme/OrderServiceReviewForgeTest.java",
                List.of("src/test/java/com/acme/OrderServiceReviewForgeTest.java"));

        assertThat(path).isEqualTo("src/test/java/com/acme/OrderServiceReviewForgeTest2.java");
    }

    @Test
    void acceptsASelfContainedJUnitClass() {
        factory.screen(new ModelGeneratedTest("OrderServiceReviewForgeTest", SOURCE, "Reproduces the race."));
    }

    @Test
    void refusesSourceWithoutATestMethod() {
        assertThatThrownBy(() -> factory.screen(new ModelGeneratedTest(
                "OrderServiceReviewForgeTest", "class OrderServiceReviewForgeTest {}", "None")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no JUnit test method");
    }

    @Test
    void refusesSourceThatStartsProcessesOrTouchesTheFileSystem() {
        String dangerous = SOURCE.replace("void oversellsUnderConcurrentReservations() {",
                "void oversellsUnderConcurrentReservations() { Runtime.getRuntime().exec(\"id\");");

        assertThatThrownBy(() -> factory.screen(
                new ModelGeneratedTest("OrderServiceReviewForgeTest", dangerous, "None")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Runtime.getRuntime");
    }

    @Test
    void refusesAClassNameThatIsNotAJavaIdentifier() {
        assertThatThrownBy(() -> factory.screen(new ModelGeneratedTest("../../etc/passwd", SOURCE, "None")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("valid Java identifier");
    }

    @Test
    void writesAnAddOnlyUnifiedDiff() {
        String diff = factory.newFileDiff("src/test/java/com/acme/OrderServiceReviewForgeTest.java", SOURCE);

        assertThat(diff).startsWith("diff --git a/src/test/java/com/acme/OrderServiceReviewForgeTest.java");
        assertThat(diff).contains("new file mode 100644");
        assertThat(diff).contains("--- /dev/null");
        assertThat(diff).contains("@@ -0,0 +1,9 @@");
        assertThat(diff.lines().filter(line -> line.startsWith("+")).count()).isEqualTo(10);
    }

    @Test
    void derivesTheFullyQualifiedSelectorFromThePackageDeclaration() {
        assertThat(factory.fullyQualifiedName(SOURCE, "OrderServiceReviewForgeTest"))
                .isEqualTo("com.acme.OrderServiceReviewForgeTest");
        assertThat(factory.fullyQualifiedName("class A {}", "A")).isEqualTo("A");
    }
}
