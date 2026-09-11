package ai.reviewforge.runner.run;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The run policy. The caller names a profile and a test selector; the runner maps those onto
 * an allowlisted command. No command string ever crosses this boundary.
 */
public record RunRequest(
        @NotBlank
        @Pattern(regexp = "^(?!/)(?!.*(?:^|/)\\.\\.(?:/|$))(?:[A-Za-z0-9._-]+/)*src/test/[A-Za-z0-9._/-]+\\.java$",
                message = "patchPath must be a relative path under a Maven module's src/test tree")
        String patchPath,

        @NotBlank
        @Size(max = 200_000)
        String patchContent,

        @NotBlank
        @Pattern(regexp = "^[A-Z_]+$", message = "profile must be an allowlisted profile name")
        String profile,

        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_.$#]{1,300}$", message = "testSelector must be a plain test identifier")
        String testSelector,

        @Min(value = 1, message = "timeoutSeconds must be positive")
        Integer timeoutSeconds
) {
}
