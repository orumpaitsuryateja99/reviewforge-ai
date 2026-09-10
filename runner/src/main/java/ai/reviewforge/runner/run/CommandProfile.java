package ai.reviewforge.runner.run;

import java.util.List;

/**
 * The complete set of commands this runner will execute. A request selects a profile by name;
 * it can never supply arguments beyond the validated test selector.
 */
public enum CommandProfile {

    MAVEN_SINGLE_TEST {
        @Override
        public List<String> command(String mavenCommand, String testSelector,
                                    String repositoryPath, boolean offline) {
            List<String> command = new java.util.ArrayList<>(List.of(
                    mavenCommand, "-B", "-ntp",
                    "-Dstyle.color=never",
                    "-Dmaven.repo.local=" + repositoryPath,
                    "-DfailIfNoTests=false",
                    "-Dsurefire.failIfNoSpecifiedTests=false",
                    "-Dmaven.test.failure.ignore=true",
                    "-Dtest=" + testSelector,
                    "test"
            ));
            if (offline) {
                command.add(1, "-o");
            }
            return List.copyOf(command);
        }
    };

    public abstract List<String> command(String mavenCommand, String testSelector,
                                         String repositoryPath, boolean offline);

    public static CommandProfile of(String name) {
        for (CommandProfile profile : values()) {
            if (profile.name().equals(name)) {
                return profile;
            }
        }
        throw new IllegalArgumentException("Unknown command profile: " + name);
    }
}
