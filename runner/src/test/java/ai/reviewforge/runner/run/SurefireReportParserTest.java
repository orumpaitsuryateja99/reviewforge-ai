package ai.reviewforge.runner.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class SurefireReportParserTest {

    private final SurefireReportParser parser = new SurefireReportParser();

    @Test
    void sumsCountsAcrossEveryReport(@TempDir Path project) throws IOException {
        writeReport(project, "TEST-com.acme.OrderTest.xml",
                "<testsuite name=\"com.acme.OrderTest\" tests=\"3\" failures=\"1\" errors=\"0\" skipped=\"1\"/>");
        writeReport(project, "TEST-com.acme.PaymentTest.xml",
                "<testsuite name=\"com.acme.PaymentTest\" tests=\"2\" failures=\"0\" errors=\"1\" skipped=\"0\"/>");

        SurefireReportParser.Counts counts = parser.parse(project);

        assertThat(counts.tests()).isEqualTo(5);
        assertThat(counts.failed()).isEqualTo(2);
        assertThat(counts.skipped()).isEqualTo(1);
        assertThat(counts.passed()).isEqualTo(2);
    }

    @Test
    void returnsZerosWhenTheBuildProducedNoReports(@TempDir Path project) {
        SurefireReportParser.Counts counts = parser.parse(project);

        assertThat(counts.tests()).isZero();
        assertThat(counts.failed()).isZero();
    }

    @Test
    void ignoresUnreadableReportsInsteadOfFailingTheRun(@TempDir Path project) throws IOException {
        writeReport(project, "TEST-broken.xml", "<testsuite tests=\"2\"");
        writeReport(project, "TEST-good.xml", "<testsuite tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");

        assertThat(parser.parse(project).tests()).isEqualTo(1);
    }

    private void writeReport(Path project, String name, String xml) throws IOException {
        Path directory = project.resolve("target/surefire-reports");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(name), xml);
    }
}
