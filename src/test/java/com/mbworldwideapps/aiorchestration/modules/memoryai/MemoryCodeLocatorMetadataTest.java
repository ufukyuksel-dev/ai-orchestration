package com.mbworldwideapps.aiorchestration.modules.memoryai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MemoryCodeLocatorMetadataTest {

    @Test
    void mcpWriteSchemaDoesNotRequireCapsuleFieldsForFileLocators() throws Exception {
        var method = java.util.Arrays.stream(com.mbworldwideapps.aiorchestration.modules.mcp.server.MemoryMcpTool.class.getMethods())
                .filter(m -> m.getName().equals("write"))
                .max(java.util.Comparator.comparingInt(java.lang.reflect.Method::getParameterCount)).orElseThrow();
        String schema = org.springaicommunity.mcp.method.tool.utils.JsonSchemaGenerator.generateForMethodInput(method);
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema)
                .path("properties").path("codeLocators").path("items");
        var required = new java.util.ArrayList<String>();
        node.path("required").forEach(n -> required.add(n.asText()));
        assertThat(required).contains("kind", "ref").doesNotContain("capsuleKind", "relationship");
        assertThatThrownBy(() -> new MemoryCodeLocator(MemoryCodeLocatorKind.CAPSULE, "target", null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("requires capsuleKind");
    }

    @Test
    void directoryCanonicalizationAndMetadataRoundTrip() {
        var locator = new MemoryCodeLocator(MemoryCodeLocatorKind.DIRECTORY, "a/b/", null, null);
        assertThat(locator.ref()).isEqualTo("a/b");
        assertThat(locator.effectiveRelationship(MemoryType.DECISION)).isEqualTo(MemoryCodeLocatorRelationship.MENTIONS);
        assertThat(MemoryCodeLocatorMetadata.read(MemoryCodeLocatorMetadata.replace(Map.of(), List.of(locator),
                MemoryScope.PROJECT, "P")).items()).containsExactly(locator);
        for (String bad : List.of("/a", "../a", "a/../b", "a//b", "a/./b", ".", "a//", "a\\b", "C:/a", "e\u0301", "a\nb"))
            assertThatThrownBy(() -> new MemoryCodeLocator(MemoryCodeLocatorKind.DIRECTORY, bad, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryCodeLocator(MemoryCodeLocatorKind.DIRECTORY, "a/b", null,
                MemoryCodeLocatorRelationship.CONSTRAINS)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void roundTripsVersionedLocatorsAndPreservesUnrelatedMetadata() {
        List<MemoryCodeLocator> locators = List.of(
                new MemoryCodeLocator(MemoryCodeLocatorKind.FILE, "./src/main/java/PaymentService.java", null,
                        MemoryCodeLocatorRelationship.MENTIONS),
                new MemoryCodeLocator(MemoryCodeLocatorKind.SYMBOL, "com.acme.PaymentService#pay(String)",
                        null, null, "src/main/java/PaymentService.java", "primary_change_point"),
                new MemoryCodeLocator(MemoryCodeLocatorKind.CAPSULE, "stable-target", "domain_flow",
                        MemoryCodeLocatorRelationship.EVIDENCES));

        Map<String, Object> metadata = MemoryCodeLocatorMetadata.replace(Map.of("owner", "scanner"), locators,
                MemoryScope.PROJECT, "PAYMENTS");
        MemoryCodeLocatorMetadata.Decoded decoded = MemoryCodeLocatorMetadata.read(metadata);

        assertThat(metadata).containsEntry("owner", "scanner");
        assertThat(decoded.present()).isTrue();
        assertThat(decoded.items()).containsExactlyElementsOf(locators.stream()
                .map(locator -> locator.kind() == MemoryCodeLocatorKind.FILE
                        ? new MemoryCodeLocator(locator.kind(), "src/main/java/PaymentService.java", null,
                                locator.relationship())
                        : locator)
                .toList());
        assertThat(decoded.items().get(1).path()).isEqualTo("src/main/java/PaymentService.java");
        assertThat(decoded.items().get(1).role()).isEqualTo("primary_change_point");
    }

    @Test
    void emptyReplacementIsAnExplicitTombstoneAndNullPreservesIt() {
        Map<String, Object> cleared = MemoryCodeLocatorMetadata.replace(Map.of("legacy", "keep"), List.of(),
                MemoryScope.PROJECT, "PAYMENTS");
        Map<String, Object> preserved = MemoryCodeLocatorMetadata.replace(cleared, null,
                MemoryScope.PROJECT, "PAYMENTS");

        assertThat(MemoryCodeLocatorMetadata.read(cleared).present()).isTrue();
        assertThat(MemoryCodeLocatorMetadata.read(cleared).items()).isEmpty();
        assertThat(preserved).isEqualTo(cleared);
    }

    @Test
    void rejectsCodeLocatorsOutsideProjectScopeAndUnsafeFilePaths() {
        MemoryCodeLocator file = new MemoryCodeLocator(MemoryCodeLocatorKind.FILE,
                "src/main/java/PaymentService.java", null, null);

        assertThatThrownBy(() -> MemoryCodeLocatorMetadata.replace(Map.of(), List.of(file), MemoryScope.GLOBAL, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project scope");
        assertThatThrownBy(() -> new MemoryCodeLocator(MemoryCodeLocatorKind.FILE, "../../etc/passwd", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parent traversal");
        assertThatThrownBy(() -> new MemoryCodeLocator(MemoryCodeLocatorKind.FILE, "/etc/passwd", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repository-relative");
    }

    @Test
    void rejectsUnsupportedEnvelopeVersionInsteadOfFallingBackSilently() {
        Map<String, Object> metadata = Map.of(MemoryCodeLocatorMetadata.METADATA_KEY,
                Map.of("version", 2, "items", List.of()));

        assertThatThrownBy(() -> MemoryCodeLocatorMetadata.read(metadata))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version");
    }
}
