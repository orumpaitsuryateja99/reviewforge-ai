package ai.reviewforge.generation;

import ai.reviewforge.common.api.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns proposed test source into a patch the server fully controls: the server picks the
 * path, screens the source, and writes the unified diff. Patches only ever add a new file.
 */
@Component
public class TestPatchFactory {

    private static final int MAX_SOURCE_CHARS = 40_000;
    private static final Pattern CLASS_NAME = Pattern.compile("^[A-Z][A-Za-z0-9_]{0,120}$");
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final List<String> FORBIDDEN_CONSTRUCTS = List.of(
            "Runtime.getRuntime", "ProcessBuilder", "System.exit", "java.net.Socket",
            "URLClassLoader", "sun.misc.Unsafe", "Files.delete", "FileSystems.getDefault"
    );

    /** Derives the conventional test path for a source file, keeping the package layout intact. */
    public String targetPath(String sourcePath, String className) {
        String directory = sourcePath.contains("src/main/java/")
                ? sourcePath.substring(0, sourcePath.indexOf("src/main/java/")) + "src/test/java/"
                + packageDirectory(sourcePath)
                : "src/test/java/";
        return directory + className + ".java";
    }

    public String uniquePath(String candidate, List<String> existingPaths) {
        if (!existingPaths.contains(candidate)) {
            return candidate;
        }
        String withoutExtension = candidate.substring(0, candidate.length() - ".java".length());
        for (int suffix = 2; suffix < 50; suffix++) {
            String next = withoutExtension + suffix + ".java";
            if (!existingPaths.contains(next)) {
                return next;
            }
        }
        throw new ApiException(HttpStatus.CONFLICT, "TEST_PATH_UNAVAILABLE",
                "Could not find an unused test file name for this finding.");
    }

    public void screen(ModelGeneratedTest proposal) {
        if (proposal == null || isBlank(proposal.className()) || isBlank(proposal.source())) {
            throw reject("The model returned an empty test class.");
        }
        if (!CLASS_NAME.matcher(proposal.className().trim()).matches()) {
            throw reject("The proposed class name is not a valid Java identifier.");
        }
        if (proposal.source().length() > MAX_SOURCE_CHARS) {
            throw reject("The proposed test source exceeds the size limit.");
        }
        if (!proposal.source().contains("@Test")) {
            throw reject("The proposed source contains no JUnit test method.");
        }
        if (!proposal.source().contains("class " + proposal.className().trim())) {
            throw reject("The proposed source does not declare the class it names.");
        }
        FORBIDDEN_CONSTRUCTS.stream()
                .filter(construct -> proposal.source().contains(construct))
                .findFirst()
                .ifPresent(construct -> {
                    throw reject("The proposed source uses a construct that is not permitted in generated tests: "
                            + construct + ".");
                });
    }

    /** Unified diff that adds one new file, matching what GitHub and the runner expect. */
    public String newFileDiff(String path, String content) {
        List<String> lines = content.lines().toList();
        StringBuilder diff = new StringBuilder()
                .append("diff --git a/").append(path).append(" b/").append(path).append('\n')
                .append("new file mode 100644\n")
                .append("--- /dev/null\n")
                .append("+++ b/").append(path).append('\n')
                .append("@@ -0,0 +1,").append(lines.size()).append(" @@\n");
        lines.forEach(line -> diff.append('+').append(line).append('\n'));
        if (!content.endsWith("\n")) {
            diff.append("\\ No newline at end of file\n");
        }
        return diff.toString();
    }

    /** Package directory of the source file, used to keep the generated test beside its subject. */
    private String packageDirectory(String sourcePath) {
        int start = sourcePath.indexOf("src/main/java/") + "src/main/java/".length();
        int end = sourcePath.lastIndexOf('/');
        return end <= start ? "" : sourcePath.substring(start, end + 1);
    }

    public String packageOf(String source) {
        Matcher matcher = PACKAGE_DECLARATION.matcher(source);
        return matcher.find() ? matcher.group(1) : "";
    }

    public String fullyQualifiedName(String source, String className) {
        String packageName = packageOf(source);
        return packageName.isEmpty() ? className : packageName + "." + className;
    }

    private static ApiException reject(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "GENERATED_TEST_REJECTED", message);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
