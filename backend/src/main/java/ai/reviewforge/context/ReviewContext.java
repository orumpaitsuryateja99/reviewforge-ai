package ai.reviewforge.context;

import java.util.List;

/**
 * Everything the review engine is allowed to see for one analysis, resolved against a
 * single immutable head commit. Repository text carried here is data, never instruction.
 */
public record ReviewContext(
        String repositoryFullName,
        int pullRequestNumber,
        String title,
        String baseRef,
        String headRef,
        String headSha,
        String buildFilePath,
        List<ContextFile> files,
        List<String> omittedPaths,
        boolean truncated
) {

    public boolean isEmpty() {
        return files.isEmpty();
    }

    public record ContextFile(
            String path,
            String status,
            String patch,
            UnifiedDiffParser.ParsedDiff diff,
            List<String> headLines,
            String existingTestPath,
            String existingTestSource
    ) {

        public int lineCount() {
            return headLines.size();
        }

        public String numberedSource() {
            StringBuilder builder = new StringBuilder();
            for (int index = 0; index < headLines.size(); index++) {
                builder.append(index + 1).append(": ").append(headLines.get(index)).append('\n');
            }
            return builder.toString();
        }

        public String lineAt(int line) {
            return line >= 1 && line <= headLines.size() ? headLines.get(line - 1) : "";
        }
    }
}
