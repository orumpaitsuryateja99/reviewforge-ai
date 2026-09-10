package ai.reviewforge.context;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the unified diff GitHub returns for a changed file. Only line bookkeeping is
 * derived here: which lines exist in the head revision and which of them the pull request
 * actually touched. Findings are later required to land on touched lines.
 */
public final class UnifiedDiffParser {

    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");

    private UnifiedDiffParser() {
    }

    public static ParsedDiff parse(String patch) {
        if (patch == null || patch.isBlank()) {
            return new ParsedDiff(List.of(), Set.of());
        }

        List<Hunk> hunks = new ArrayList<>();
        Set<Integer> changedLines = new LinkedHashSet<>();
        int newLine = 0;
        Hunk current = null;

        for (String line : patch.split("\n", -1)) {
            Matcher header = HUNK_HEADER.matcher(line);
            if (header.matches()) {
                current = new Hunk(
                        Integer.parseInt(header.group(1)),
                        header.group(2) == null ? 1 : Integer.parseInt(header.group(2)),
                        Integer.parseInt(header.group(3)),
                        header.group(4) == null ? 1 : Integer.parseInt(header.group(4))
                );
                hunks.add(current);
                newLine = current.newStart();
                continue;
            }
            if (current == null || line.startsWith("\\")) {
                continue;
            }
            if (line.startsWith("+")) {
                changedLines.add(newLine++);
            } else if (line.startsWith("-")) {
                // A deleted line has no head-revision number of its own. Mark the line that now
                // occupies its position: for a replacement that is the new line, and for a pure
                // deletion it is the code that closed the gap.
                changedLines.add(newLine);
            } else {
                newLine++;
            }
        }

        return new ParsedDiff(List.copyOf(hunks), Set.copyOf(changedLines));
    }

    public record Hunk(int oldStart, int oldCount, int newStart, int newCount) {

        public int newEnd() {
            return newStart + Math.max(newCount, 1) - 1;
        }
    }

    public record ParsedDiff(List<Hunk> hunks, Set<Integer> changedLines) {

        public boolean covers(int startLine, int endLine) {
            for (int line = startLine; line <= endLine; line++) {
                if (changedLines.contains(line)) {
                    return true;
                }
            }
            return false;
        }
    }
}
