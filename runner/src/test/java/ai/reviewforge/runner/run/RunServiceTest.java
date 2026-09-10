package ai.reviewforge.runner.run;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the execution path with a stub build command, so workspace handling, patch
 * placement, module detection, timeouts, and cleanup are covered without downloading Maven
 * dependencies.
 */
class RunServiceTest {

    private static final String TEST_SOURCE = """
            package com.acme;

            import org.junit.jupiter.api.Test;

            class OrderServiceReviewForgeTest {
                @Test
                void oversells() {
                }
            }
            """;

    @Test
    void reportsPassedAndPlacesThePatchInsideTheModule(@TempDir Path scratch) throws IOException {
        // The stub prints back whatever test source it finds, proving the patch reached the module.
        Path maven = stubCommand(scratch, """
                #!/bin/sh
                mkdir -p target/surefire-reports
                cat > target/surefire-reports/TEST-com.acme.OrderServiceReviewForgeTest.xml <<'XML'
                <testsuite tests="1" failures="0" errors="0" skipped="0"/>
                XML
                find src/test -name '*.java' -exec cat {} +
                exit 0
                """);

        RunResult result = service(scratch, maven).run(snapshot(Map.of(
                "acme-orders-abc/pom.xml", "<project/>",
                "acme-orders-abc/src/main/java/com/acme/OrderService.java", "class OrderService {}"
        )), request("src/test/java/com/acme/OrderServiceReviewForgeTest.java", 60));

        assertThat(result.status()).isEqualTo(RunResult.PASSED);
        assertThat(result.exitCode()).isZero();
        assertThat(result.testsRun()).isEqualTo(1);
        assertThat(result.testsPassed()).isEqualTo(1);
        assertThat(result.stdout()).contains("class OrderServiceReviewForgeTest");
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    void reportsFailedWhenTheSuiteRecordsAFailure(@TempDir Path scratch) throws IOException {
        Path maven = stubCommand(scratch, """
                #!/bin/sh
                mkdir -p target/surefire-reports
                cat > target/surefire-reports/TEST-com.acme.OrderServiceReviewForgeTest.xml <<'XML'
                <testsuite tests="1" failures="1" errors="0" skipped="0"/>
                XML
                exit 0
                """);

        RunResult result = service(scratch, maven).run(snapshot(Map.of(
                "acme-orders-abc/pom.xml", "<project/>"
        )), request("src/test/java/com/acme/OrderServiceReviewForgeTest.java", 60));

        assertThat(result.status()).isEqualTo(RunResult.FAILED);
        assertThat(result.testsFailed()).isEqualTo(1);
        assertThat(result.testsPassed()).isZero();
    }

    @Test
    void killsARunThatOverrunsItsTimeout(@TempDir Path scratch) throws IOException {
        Path maven = stubCommand(scratch, """
                #!/bin/sh
                sleep 30
                """);

        RunResult result = service(scratch, maven).run(snapshot(Map.of(
                "acme-orders-abc/pom.xml", "<project/>"
        )), request("src/test/java/com/acme/OrderServiceReviewForgeTest.java", 1));

        assertThat(result.status()).isEqualTo(RunResult.TIMED_OUT);
        assertThat(result.timedOut()).isTrue();
    }

    @Test
    void refusesASnapshotWithNoMavenModule(@TempDir Path scratch) throws IOException {
        Path maven = stubCommand(scratch, "#!/bin/sh\nexit 0\n");

        RunResult result = service(scratch, maven).run(snapshot(Map.of(
                "acme-orders-abc/README.md", "no build file here"
        )), request("src/test/java/com/acme/OrderServiceReviewForgeTest.java", 60));

        assertThat(result.status()).isEqualTo(RunResult.INFRASTRUCTURE_ERROR);
        assertThat(result.stderr()).contains("No Maven module");
    }

    @Test
    void refusesAPatchPathThatEscapesTheProject(@TempDir Path scratch) throws IOException {
        Path maven = stubCommand(scratch, "#!/bin/sh\nexit 0\n");

        RunResult result = service(scratch, maven).run(snapshot(Map.of(
                "acme-orders-abc/pom.xml", "<project/>"
        )), request("../../escape/Test.java", 60));

        assertThat(result.status()).isEqualTo(RunResult.INFRASTRUCTURE_ERROR);
        assertThat(result.stderr()).contains("escapes the project root");
    }

    @Test
    void deletesTheWorkspaceWhateverTheOutcome(@TempDir Path scratch) throws IOException {
        Path workspaceRoot = scratch.resolve("runs");
        Path maven = stubCommand(scratch, "#!/bin/sh\nexit 1\n");

        new RunService(properties(workspaceRoot, maven), extractor(workspaceRoot, maven), new SurefireReportParser())
                .run(snapshot(Map.of("acme-orders-abc/pom.xml", "<project/>")),
                        request("src/test/java/com/acme/OrderServiceReviewForgeTest.java", 60));

        try (var entries = Files.list(workspaceRoot)) {
            assertThat(entries).isEmpty();
        }
    }

    private RunService service(Path scratch, Path maven) {
        Path workspaceRoot = scratch.resolve("runs");
        RunnerProperties properties = properties(workspaceRoot, maven);
        return new RunService(properties, new SnapshotExtractor(properties), new SurefireReportParser());
    }

    private SnapshotExtractor extractor(Path workspaceRoot, Path maven) {
        return new SnapshotExtractor(properties(workspaceRoot, maven));
    }

    private RunnerProperties properties(Path workspaceRoot, Path maven) {
        return new RunnerProperties(
                workspaceRoot.toString(), 600, 300, 65_536, 50_000_000L, 5_000,
                maven.toString(), workspaceRoot.resolve("cache").toString(),
                System.getProperty("java.io.tmpdir"), false);
    }

    private RunRequest request(String patchPath, int timeoutSeconds) {
        return new RunRequest(patchPath, TEST_SOURCE, "MAVEN_SINGLE_TEST",
                "com.acme.OrderServiceReviewForgeTest", timeoutSeconds);
    }

    private Path stubCommand(Path scratch, String script) throws IOException {
        Path command = scratch.resolve("stub-maven");
        Files.writeString(command, script);
        Files.setPosixFilePermissions(command, PosixFilePermissions.fromString("rwxr-xr-x"));
        return command;
    }

    private MockMultipartFile snapshot(Map<String, String> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(buffer))) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                byte[] content = entry.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry tarEntry = new TarArchiveEntry(entry.getKey());
                tarEntry.setSize(content.length);
                tar.putArchiveEntry(tarEntry);
                tar.write(content);
                tar.closeArchiveEntry();
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return new MockMultipartFile("snapshot", "snapshot.tar.gz",
                "application/octet-stream", buffer.toByteArray());
    }
}
