package com.mbworldwideapps.aiorchestration.modules.plancompiler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PlanSymbolGlobMatcherTest {
    @Test
    void matchesWholeNamesAndQualifiedSignaturesWithoutPathOrRegexSemantics() {
        assertThat(PlanSymbolGlobMatcher.matches("**Controller", "WorkdayController")).isTrue();
        assertThat(PlanSymbolGlobMatcher.matches("**Controller", "com.acme.WorkdayController")).isTrue();
        assertThat(PlanSymbolGlobMatcher.matches("**Controller", "com.acme.WorkdayController#createPilot")).isFalse();
        assertThat(PlanSymbolGlobMatcher.matches("**Controller", "WorkdayControllerTest")).isFalse();
        assertThat(PlanSymbolGlobMatcher.matches("**Controller", "workdaycontroller")).isFalse();
        assertThat(PlanSymbolGlobMatcher.matches("com.acme.*#create(java.lang.String[])",
                "com.acme.WorkdayController#create(java.lang.String[])")).isTrue();
        assertThat(PlanSymbolGlobMatcher.matches("com.acme.*", "comXacmeYController")).isFalse();
        assertThat(PlanSymbolGlobMatcher.matches("A$Nested#x()", "A$Nested#x()")).isTrue();
        assertThat(PlanSymbolGlobMatcher.matches("*", "A")).isTrue();
        assertThat(PlanSymbolGlobMatcher.matches("AB*CD", "ABCD")).isTrue();
        assertThat(PlanSymbolGlobMatcher.matches("É*", "E\u0301Controller")).isTrue();
    }

    @Test
    void boundsInputsAndKeepsPathRecursiveWildcardContractSeparate() {
        assertThatThrownBy(() -> PlanSymbolGlobMatcher.matches("*".repeat(257), "A"))
                .hasMessageContaining("bound");
        assertThatThrownBy(() -> PlanSymbolGlobMatcher.matches("*", "A".repeat(4097)))
                .hasMessageContaining("bound");
        assertThatThrownBy(() -> PlanSymbolGlobMatcher.matches("A\n*", "A"))
                .hasMessageContaining("canonical");
        assertThatThrownBy(() -> PlanSymbolGlobMatcher.matches("*", "*Controller"))
                .hasMessageContaining("wildcards");
        assertThatThrownBy(() -> PlanPathGlobMatcher.matches("**Controller.java", "Controller.java"))
                .hasMessageContaining("complete path segment");
        assertThat(PlanPathGlobMatcher.matches("**/*Controller.java", "src/WorkdayController.java")).isTrue();
    }
}
