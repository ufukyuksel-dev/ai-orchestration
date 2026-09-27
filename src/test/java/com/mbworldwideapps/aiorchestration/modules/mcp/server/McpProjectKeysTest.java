package com.mbworldwideapps.aiorchestration.modules.mcp.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

class McpProjectKeysTest {

    @Test
    void localTrustNormalizesRequestedProjectKey() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        assertThat(McpProjectKeys.effective(context, "mobil-mobileapp-be"))
                .isEqualTo("MOBIL_MOBILEAPP_BE");
    }

    @Test
    void bearerContextKeepsExactProjectKeyBoundary() {
        McpClientContext context = new McpClientContext("project-a", "codex", "mcp_abcd", List.of());

        assertThat(McpProjectKeys.effective(context, "project-a")).isEqualTo("project-a");
        assertThatThrownBy(() -> McpProjectKeys.effective(context, "PROJECT_A"))
                .isInstanceOf(McpAccessException.class);
    }

    @Test
    void localScanDerivesProjectKeyFromRootPath() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        assertThat(McpProjectKeys.forScan(context, null,
                "/home/dev/projects/MobileAppBackend/mobil-mobileapp-be"))
                .matches("MOBIL_MOBILEAPP_BE_[0-9A-F]{8}");
    }

    @Test
    void localScanKeepsDefaultProjectKeyForCurrentWorkingDirectory() {
        // Independent of the checkout directory name: derive this directory's path key with an unrelated
        // context first, then use that directory's slug as the default project key.
        McpClientContext unrelated = new McpClientContext("SOME_OTHER_PROJECT", "codex", "local", List.of());
        String derived = McpProjectKeys.forScan(unrelated, null, ".");
        assertThat(derived).matches("[A-Z0-9_]+_[0-9A-F]{8}");
        String currentDirectorySlug = derived.substring(0, derived.length() - 9);

        McpClientContext matching = new McpClientContext(currentDirectorySlug, "codex", "local", List.of());
        assertThat(McpProjectKeys.forScan(matching, null, ".")).isEqualTo(currentDirectorySlug);
        assertThat(McpProjectKeys.forScan(unrelated, null, ".")).isEqualTo(derived).isNotEqualTo("SOME_OTHER_PROJECT");
    }

    @Test
    void pathDerivedKeysDoNotMergePunctuationCollisions() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        String hyphen = McpProjectKeys.forScan(context, null, "/tmp/foo-bar");
        String underscore = McpProjectKeys.forScan(context, null, "/tmp/foo_bar");
        String dot = McpProjectKeys.forScan(context, null, "/tmp/foo.bar");

        assertThat(hyphen).startsWith("FOO_BAR_");
        assertThat(underscore).startsWith("FOO_BAR_");
        assertThat(dot).startsWith("FOO_BAR_");
        assertThat(List.of(hyphen, underscore, dot)).doesNotHaveDuplicates();
    }

    @Test
    void pathDerivedKeysKeepNonAsciiReposOutOfDefaultProject() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        assertThat(McpProjectKeys.forScan(context, null, "/tmp/ödeme-ışığı"))
                .matches("ODEME_ISIGI_[0-9A-F]{8}")
                .isNotEqualTo("AI_ORCHESTRATION");
    }

    @Test
    void localMemoryWriteRejectsFileSourceRefWithoutProjectKey() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        assertThatThrownBy(() -> McpProjectKeys.forMemoryWrite(context, null,
                "src/main/java/com/acme/CampaignService.java"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("explicit projectKey");
        assertThatThrownBy(() -> McpProjectKeys.forMemoryWrite(context, null,
                "/home/dev/projects/MobileAppBackend/mobil-mobileapp-be/src/main/java/CampaignService.java"))
                .isInstanceOf(McpAccessException.class)
                .hasMessageContaining("explicit projectKey");
    }

    @Test
    void localMemoryWriteAllowsExplicitProjectKeyWithRelativeFileSourceRef() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        assertThat(McpProjectKeys.forMemoryWrite(context, "mobil-mobileapp-be",
                "src/main/java/com/acme/CampaignService.java"))
                .isEqualTo("MOBIL_MOBILEAPP_BE");
    }

    @Test
    void localMemoryWriteInfersProjectKeyFromAbsoluteRepositoryRootSourceRef() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        assertThat(McpProjectKeys.forMemoryWrite(context, null,
                "/home/dev/projects/MobileAppBackend/mobil-mobileapp-be"))
                .matches("MOBIL_MOBILEAPP_BE_[0-9A-F]{8}");
    }

    @Test
    void localMemoryWriteKeepsContextForNonPathSourceRef() {
        McpClientContext context = new McpClientContext("AI_ORCHESTRATION", "codex", "local", List.of());

        assertThat(McpProjectKeys.forMemoryWrite(context, null, "external-ticket-123"))
                .isEqualTo("AI_ORCHESTRATION");
    }
}
