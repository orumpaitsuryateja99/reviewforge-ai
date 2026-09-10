package ai.reviewforge.runner.run;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotExtractorTest {

    private final SnapshotExtractor extractor = new SnapshotExtractor(new RunnerProperties(
            "/tmp/reviewforge-runs", 600, 300, 65536, 1_000_000L, 100, "mvn",
            "/home/reviewforge/.m2/repository", "/home/reviewforge", false));

    @Test
    void unwrapsTheArchiveRootTheWayGitHubShipsIt(@TempDir Path workspace) throws IOException {
        byte[] archive = archive(new LinkedHashMap<>(Map.of(
                "acme-orders-abc123/pom.xml", "<project/>",
                "acme-orders-abc123/src/main/java/A.java", "class A {}"
        )));

        Path root = extractor.extract(new ByteArrayInputStream(archive), workspace);

        assertThat(root.getFileName()).hasToString("acme-orders-abc123");
        assertThat(Files.readString(root.resolve("pom.xml"))).isEqualTo("<project/>");
    }

    @Test
    void refusesEntriesThatEscapeTheWorkspace(@TempDir Path workspace) {
        byte[] archive = archive(new LinkedHashMap<>(Map.of("repo/../../escape.txt", "owned")));

        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(archive), workspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("outside the workspace");
    }

    @Test
    void refusesArchivesWithMoreEntriesThanTheLimit(@TempDir Path workspace) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int index = 0; index < 150; index++) {
            entries.put("repo/file" + index + ".txt", "x");
        }

        assertThatThrownBy(() -> extractor.extract(new ByteArrayInputStream(archive(entries)), workspace))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("more entries than the runner allows");
    }

    private byte[] archive(Map<String, String> entries) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(new GzipCompressorOutputStream(buffer))) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Map.Entry<String, String> entry : entries.entrySet()) {
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
        return buffer.toByteArray();
    }
}
