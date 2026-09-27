package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlanPathGlobMatcherTest {

    @Test
    void doubleStarMatchesZeroOrManyWholePathSegments() {
        assertThat(PlanPathGlobMatcher.matches("**/*Controller.java", "AccountController.java")).isTrue();
        assertThat(PlanPathGlobMatcher.matches(
                "**/*Controller.java", "src/main/java/com/acme/AccountController.java")).isTrue();
        assertThat(PlanPathGlobMatcher.matches("src/**/Account.java", "src/Account.java")).isTrue();
        assertThat(PlanPathGlobMatcher.matches("src/**/Account.java", "src/a/b/Account.java")).isTrue();
        assertThat(PlanPathGlobMatcher.matches("src/*/Account.java", "src/a/b/Account.java")).isFalse();
    }

    @Test
    void singleStarNeverCrossesAPathBoundary() {
        assertThat(PlanPathGlobMatcher.matches("src/*/A*.java", "src/main/Account.java")).isTrue();
        assertThat(PlanPathGlobMatcher.matches("src/*/A*.java", "src/main/web/Account.java")).isFalse();
        assertThat(PlanPathGlobMatcher.matches("*.java", "nested/Account.java")).isFalse();
    }

    @Test
    void malformedOrUnboundedInputsAreRejectedBeforeMatching() {
        for (String invalid : new String[] {
                "", "/src/**", "src\\**", "src/../**", "src/**x/A.java", "src//A.java"
        }) {
            assertThatThrownBy(() -> PlanPathGlobMatcher.matches(invalid, "src/A.java"))
                    .as("pattern %s", invalid)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> PlanPathGlobMatcher.matches("**", "../outside.java"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PlanPathGlobMatcher.matches("a".repeat(257), "A.java"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
