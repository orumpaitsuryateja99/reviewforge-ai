package ai.reviewforge.runner.run;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Executes one generated test in a throwaway workspace. Everything the process may do is
 * decided here: the command comes from an allowlisted profile, the environment is rebuilt
 * from scratch, and the workspace is deleted whatever the outcome.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final RunnerProperties properties;
    private final SnapshotExtractor extractor;
    private final SurefireReportParser reportParser;

    public RunService(RunnerProperties properties, SnapshotExtractor extractor, SurefireReportParser reportParser) {
        this.properties = properties;
        this.extractor = extractor;
        this.reportParser = reportParser;
    }

    public RunResult run(MultipartFile snapshot, RunRequest request) {
        CommandProfile profile = CommandProfile.of(request.profile());
        int timeoutSeconds = Math.min(
                request.timeoutSeconds() == null ? properties.defaultRunSeconds() : request.timeoutSeconds(),
                properties.maxRunSeconds()
        );

        Path workspace = null;
        long startedAt = System.nanoTime();
        try {
            workspace = Files.createTempDirectory(workspaceRoot(), "run-");
            Path projectRoot;
            try (InputStream input = snapshot.getInputStream()) {
                projectRoot = extractor.extract(input, workspace);
            }

            Path patchTarget = projectRoot.resolve(request.patchPath()).normalize();
            if (!patchTarget.startsWith(projectRoot)) {
                return infrastructureError("The patch path escapes the project root.", startedAt);
            }
            Files.createDirectories(patchTarget.getParent());
            Files.writeString(patchTarget, request.patchContent(), StandardCharsets.UTF_8);

            Path moduleRoot = moduleRootFor(projectRoot, patchTarget);
            if (!Files.exists(moduleRoot.resolve("pom.xml"))) {
                return infrastructureError("No Maven module was found for the patched test.", startedAt);
            }

            deleteBuildOutputs(moduleRoot);
            Path runHome = workspace.resolve(".home");
            Files.createDirectories(runHome);
            return execute(profile, request.testSelector(), moduleRoot, runHome, timeoutSeconds, startedAt);
        } catch (IOException exception) {
            log.warn("Run failed to prepare workspace: {}", exception.getMessage());
            return infrastructureError("The workspace could not be prepared: " + exception.getMessage(), startedAt);
        } finally {
            deleteRecursively(workspace);
        }
    }

    private RunResult execute(CommandProfile profile, String testSelector, Path moduleRoot, Path runHome,
                              int timeoutSeconds, long startedAt) throws IOException {
        List<String> command = profile.command(properties.mavenCommand(), testSelector,
                properties.mavenRepositoryPath(), properties.offline());
        ProcessBuilder builder = new ProcessBuilder(command).directory(moduleRoot.toFile());

        Map<String, String> environment = builder.environment();
        environment.clear();
        environment.put("PATH", System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin"));
        // HOME is unique to this run. A malicious build cannot persist Maven settings or
        // credentials into the next workspace; the dependency repository is a separate,
        // read-only image directory selected by the allowlisted command.
        environment.put("HOME", runHome.toString());
        environment.put("JAVA_HOME", System.getenv().getOrDefault("JAVA_HOME", ""));
        environment.put("MAVEN_OPTS", "-Xmx1g");
        environment.put("LANG", "C.UTF-8");

        Process process = builder.start();
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread outReader = readerThread(process.getInputStream(), stdout);
        Thread errReader = readerThread(process.getErrorStream(), stderr);

        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            terminate(process);
            join(outReader);
            join(errReader);
            return infrastructureError("The run was interrupted.", startedAt);
        }

        if (!finished) {
            terminate(process);
            join(outReader);
            join(errReader);
            return new RunResult(RunResult.TIMED_OUT, profile.name(), -1, 0, 0, 0, 0,
                    bounded(stdout), bounded(stderr), true, elapsed(startedAt));
        }

        join(outReader);
        join(errReader);
        int exitCode = process.exitValue();
        SurefireReportParser.Counts counts = reportParser.parse(moduleRoot);
        String status = counts.failed() == 0 && exitCode == 0 && counts.tests() > 0
                ? RunResult.PASSED
                : RunResult.FAILED;
        if (counts.tests() == 0) {
            status = RunResult.INFRASTRUCTURE_ERROR;
        }

        return new RunResult(status, profile.name(), exitCode, counts.tests(), counts.passed(),
                counts.failed(), counts.skipped(), bounded(stdout), bounded(stderr), false, elapsed(startedAt));
    }

    private Path moduleRootFor(Path projectRoot, Path patchTarget) {
        Path directory = patchTarget.getParent();
        while (directory != null && directory.startsWith(projectRoot)) {
            if (Files.exists(directory.resolve("pom.xml"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        return projectRoot;
    }

    /** Removes checked-in Maven reports so only this execution can affect the result. */
    private void deleteBuildOutputs(Path moduleRoot) throws IOException {
        List<Path> targets;
        try (var paths = Files.walk(moduleRoot, 16)) {
            targets = paths
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName() != null && "target".equals(path.getFileName().toString()))
                    .sorted(Comparator.reverseOrder())
                    .toList();
        }
        for (Path target : targets) {
            deleteTree(target);
        }
    }

    private void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    /** Best-effort process-tree termination also works where child inspection is restricted. */
    private void terminate(Process process) {
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException exception) {
            log.debug("Could not enumerate child processes during termination: {}", exception.getMessage());
        }
        process.destroyForcibly();
        close(process.getInputStream());
        close(process.getErrorStream());
        close(process.getOutputStream());
    }

    private void close(java.io.Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException exception) {
            log.debug("Could not close process stream: {}", exception.getMessage());
        }
    }

    private Thread readerThread(InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (InputStream input = stream) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int read;
                while ((read = input.read(chunk)) != -1) {
                    if (buffer.size() < properties.maxOutputBytes() * 4) {
                        buffer.write(chunk, 0, read);
                    }
                }
                sink.append(buffer.toString(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                sink.append("\n[output truncated: ").append(exception.getMessage()).append(']');
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void join(Thread thread) {
        try {
            thread.join(5_000);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private String bounded(StringBuilder output) {
        String text = output.toString();
        int limit = properties.maxOutputBytes();
        if (text.length() <= limit) {
            return text;
        }
        // Keep the tail: Maven prints failures and the reactor summary last.
        return "[truncated to the last " + limit + " characters]\n" + text.substring(text.length() - limit);
    }

    private Path workspaceRoot() throws IOException {
        Path root = Path.of(properties.workspaceRoot());
        Files.createDirectories(root);
        return root;
    }

    private RunResult infrastructureError(String message, long startedAt) {
        return new RunResult(RunResult.INFRASTRUCTURE_ERROR, "NONE", -1, 0, 0, 0, 0,
                "", message, false, elapsed(startedAt));
    }

    private long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException exception) {
                    log.debug("Could not delete {}", entry);
                }
            });
        } catch (IOException exception) {
            log.warn("Workspace {} could not be removed: {}", path, exception.getMessage());
        }
    }
}
