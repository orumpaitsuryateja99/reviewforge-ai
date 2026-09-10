package ai.reviewforge.runner.run;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.stereotype.Component;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Extracts a gzipped tar snapshot into a workspace. Entries that escape the workspace, links,
 * device nodes, and oversized archives are refused rather than sanitised.
 */
@Component
public class SnapshotExtractor {

    private final RunnerProperties properties;

    public SnapshotExtractor(RunnerProperties properties) {
        this.properties = properties;
    }

    /**
     * @return the single root directory of the archive, which is where the project actually lives
     *         (GitHub archives wrap everything in {@code owner-repo-sha/}).
     */
    public Path extract(InputStream snapshot, Path destination) throws IOException {
        Files.createDirectories(destination);
        long totalBytes = 0;
        int entries = 0;
        Set<String> roots = new java.util.LinkedHashSet<>();

        try (TarArchiveInputStream archive = new TarArchiveInputStream(
                new GzipCompressorInputStream(new BufferedInputStream(snapshot)))) {
            TarArchiveEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > properties.maxExtractedEntries()) {
                    throw new IOException("The snapshot contains more entries than the runner allows.");
                }
                if (entry.isSymbolicLink() || entry.isLink() || entry.isCharacterDevice()
                        || entry.isBlockDevice() || entry.isFIFO()) {
                    continue;
                }

                Path target = destination.resolve(entry.getName()).normalize();
                if (!target.startsWith(destination)) {
                    throw new IOException("The snapshot contains an entry outside the workspace: " + entry.getName());
                }
                rootOf(entry.getName()).ifPresent(roots::add);

                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                totalBytes += entry.getSize();
                if (totalBytes > properties.maxExtractedBytes()) {
                    throw new IOException("The snapshot expands beyond the runner's size limit.");
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = Files.newOutputStream(target)) {
                    archive.transferTo(output);
                }
            }
        }

        if (roots.size() == 1) {
            Path root = destination.resolve(roots.iterator().next());
            if (Files.isDirectory(root)) {
                return root;
            }
        }
        return destination;
    }

    private java.util.Optional<String> rootOf(String name) {
        int slash = name.indexOf('/');
        if (slash <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(name.substring(0, slash));
    }
}
