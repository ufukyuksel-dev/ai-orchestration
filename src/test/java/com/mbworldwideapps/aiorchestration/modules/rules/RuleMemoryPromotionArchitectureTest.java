package com.mbworldwideapps.aiorchestration.modules.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.MemoryRetrievalService;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryActivationPolicy;
import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryInjectionFilter;

import org.junit.jupiter.api.Test;

class RuleMemoryPromotionArchitectureTest {

    @Test
    void onlyPromotionServiceMayDependOnTheMemoryPromotionPortOutsideItsOwnerPackage() throws IOException {
        Path sourceRoot = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        try (var paths = Files.walk(sourceRoot)) {
            Set<String> importers = paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> containsImport(path,
                            "import com.mbworldwideapps.aiorchestration.modules.memoryai.RuleMemoryPromotionPort;"))
                    .map(path -> sourceRoot.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toSet());

            assertThat(importers).containsExactly(
                    "com/mbworldwideapps/aiorchestration/modules/rules/RulePromotionService.java");
        }
    }

    @Test
    void productionMemoryServiceExposesNoFailOpenConstructorOrPolicyFactory() {
        var publicConstructors = java.util.Arrays.stream(MemoryService.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .toList();

        assertThat(publicConstructors).singleElement().satisfies(constructor -> {
            assertThat(java.util.Arrays.stream(constructor.getParameterTypes())
                    .map(Class::getSimpleName))
                    .contains("RuleMemoryActivationPolicy", "RuleMemoryLinkLookup");
        });
        assertThat(java.util.Arrays.stream(RuleMemoryActivationPolicy.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("disabled", "enabledForTests");
    }

    @Test
    void mutationMethodsStayPackageOwnedUntilAnAuditedAuthoringFacadeExists() throws NoSuchMethodException {
        assertThat(Modifier.isPublic(RulePromotionService.class
                .getDeclaredMethod("promote", PromotionRequest.class).getModifiers())).isFalse();
        assertThat(Modifier.isPublic(RulePromotionService.class
                .getDeclaredMethod("promoteVersion", java.util.UUID.class, int.class, String.class,
                        PromotionRequest.class)
                .getModifiers())).isFalse();
        assertThat(Modifier.isPublic(RulePromotionService.class
                .getDeclaredMethod("deprecate", RuleDeprecationRequest.class).getModifiers())).isFalse();
    }

    @Test
    void productionRetrievalServiceHasOnePublicRuleAwareConstructor() {
        var publicConstructors = java.util.Arrays.stream(MemoryRetrievalService.class.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .toList();

        assertThat(publicConstructors).singleElement().satisfies(constructor ->
                assertThat(constructor.getParameterTypes()).contains(RuleMemoryInjectionFilter.class));
    }

    private static boolean containsImport(Path path, String expectedImport) {
        try {
            return Files.readString(path).contains(expectedImport);
        } catch (IOException e) {
            throw new IllegalStateException("cannot inspect source dependency: " + path, e);
        }
    }
}
