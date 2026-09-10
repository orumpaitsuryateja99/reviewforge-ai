package ai.reviewforge.runner.run;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommandProfileTest {

    @Test
    void buildsTheAllowlistedMavenInvocation() {
        List<String> command = CommandProfile.MAVEN_SINGLE_TEST.command(
                "mvn", "com.acme.OrderTest", "/cache/repository", false);

        assertThat(command).startsWith("mvn", "-B", "-ntp");
        assertThat(command).contains("-Dtest=com.acme.OrderTest");
        assertThat(command).contains("-Dmaven.repo.local=/cache/repository");
        assertThat(command).endsWith("test");
        assertThat(command).doesNotContain("-o");
    }

    @Test
    void addsTheOfflineFlagWhenTheRunnerIsSandboxedFromTheNetwork() {
        assertThat(CommandProfile.MAVEN_SINGLE_TEST.command(
                "mvn", "com.acme.OrderTest", "/cache/repository", true))
                .containsSequence("mvn", "-o", "-B");
    }

    @Test
    void refusesAProfileThatIsNotOnTheAllowlist() {
        assertThatThrownBy(() -> CommandProfile.of("BASH"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown command profile");
    }
}
