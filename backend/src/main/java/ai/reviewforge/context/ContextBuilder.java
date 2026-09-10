package ai.reviewforge.context;

import ai.reviewforge.config.ReviewProperties;
import ai.reviewforge.github.client.GitHubApiClient;
import ai.reviewforge.github.persistence.GitHubRepositoryRepository.RepositoryRecord;
import ai.reviewforge.github.persistence.PullRequestRepository.PullRequestRecord;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Builds bounded review context from the exact head commit of a pull request. Files are
 * fetched at {@code head_sha}, never at a branch name, so evidence cannot drift while an
 * analysis is running.
 */
@Service
public class ContextBuilder {

    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");
    public static final int MAX_TREE_ENTRIES = 20_000;
    private static final int MAX_TEST_SOURCE_CHARS = 6_000;

    private final GitHubApiClient apiClient;
    private final ReviewProperties properties;

    public ContextBuilder(GitHubApiClient apiClient, ReviewProperties properties) {
        this.apiClient = apiClient;
        this.properties = properties;
    }

    public ReviewContext build(String token, RepositoryRecord repository, PullRequestRecord pullRequest) {
        GitHubApiClient.ChangedFilesResult changed = apiClient.compareFiles(
                token, repository.owner(), repository.name(), pullRequest.baseSha(), pullRequest.headSha()
        );

        List<String> treePaths = safeTreePaths(token, repository, pullRequest.headSha());
        List<ReviewContext.ContextFile> files = new ArrayList<>();
        List<String> omitted = new ArrayList<>();
        int budget = properties.maxContextBytes();

        for (GitHubApiClient.ChangedFile file : changed.files()) {
            if (files.size() >= properties.maxFiles()) {
                omitted.add(file.filename() + " (file limit reached)");
                continue;
            }
            if ("removed".equalsIgnoreCase(file.status())) {
                omitted.add(file.filename() + " (deleted at head)");
                continue;
            }
            if (!properties.reviewable(file.filename())) {
                omitted.add(file.filename() + " (not a reviewable source type)");
                continue;
            }
            if (file.patch() == null || file.patch().isBlank()) {
                omitted.add(file.filename() + " (no patch available)");
                continue;
            }

            Optional<String> content = apiClient.getFileContent(
                    token, repository.owner(), repository.name(), file.filename(), pullRequest.headSha()
            );
            if (content.isEmpty()) {
                omitted.add(file.filename() + " (unreadable at head commit)");
                continue;
            }
            String source = content.get();
            if (source.length() > properties.maxFileBytes()) {
                omitted.add(file.filename() + " (larger than the per-file context limit)");
                continue;
            }
            if (source.length() > budget) {
                omitted.add(file.filename() + " (context budget exhausted)");
                continue;
            }
            budget -= source.length();

            String testPath = findExistingTest(treePaths, file.filename());
            String testSource = testPath == null ? null : readTestSource(token, repository, pullRequest.headSha(), testPath);

            files.add(new ReviewContext.ContextFile(
                    file.filename(),
                    file.status().toUpperCase(Locale.ROOT),
                    file.patch(),
                    UnifiedDiffParser.parse(file.patch()),
                    source.lines().toList(),
                    testPath,
                    testSource
            ));
        }

        return new ReviewContext(
                repository.fullName(),
                pullRequest.number(),
                pullRequest.title(),
                pullRequest.baseRef(),
                pullRequest.headRef(),
                pullRequest.headSha(),
                findBuildFile(treePaths),
                List.copyOf(files),
                List.copyOf(omitted),
                changed.truncated() || !omitted.isEmpty()
        );
    }

    private List<String> safeTreePaths(String token, RepositoryRecord repository, String headSha) {
        try {
            return apiClient.listTreePaths(token, repository.owner(), repository.name(), headSha, MAX_TREE_ENTRIES);
        } catch (RuntimeException exception) {
            // A missing or oversized tree only costs test-convention context; the review still runs.
            return List.of();
        }
    }

    private String readTestSource(String token, RepositoryRecord repository, String headSha, String path) {
        return apiClient.getFileContent(token, repository.owner(), repository.name(), path, headSha)
                .map(source -> source.length() > MAX_TEST_SOURCE_CHARS
                        ? source.substring(0, MAX_TEST_SOURCE_CHARS)
                        : source)
                .orElse(null);
    }

    /** Locates the conventional test for a source file, for example {@code FooTest.java} for {@code Foo.java}. */
    public static String findExistingTest(List<String> treePaths, String sourcePath) {
        String fileName = sourcePath.substring(sourcePath.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        if (dot <= 0) {
            return null;
        }
        String baseName = fileName.substring(0, dot);
        String extension = fileName.substring(dot);
        List<String> candidates = List.of(baseName + "Test" + extension, baseName + "Tests" + extension, baseName + "IT" + extension);
        return treePaths.stream()
                .filter(path -> candidates.contains(path.substring(path.lastIndexOf('/') + 1)))
                .findFirst()
                .orElse(null);
    }

    public static String findBuildFile(List<String> treePaths) {
        return treePaths.stream()
                .filter(path -> BUILD_FILES.contains(path))
                .findFirst()
                .orElse(null);
    }
}
