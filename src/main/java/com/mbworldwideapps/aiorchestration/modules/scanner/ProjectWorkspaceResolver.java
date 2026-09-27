package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.nio.file.Path;

/**
 * Authority for project/root bindings. Resolution is read-only; registration is explicit.
 */
public interface ProjectWorkspaceResolver {

    ProjectWorkspace resolve(String projectKey);

    ProjectWorkspace resolve(String projectKey, String expectedRepositoryFingerprint);

    ProjectWorkspace resolveByPath(Path path);

    /** Validate and register one canonical Git root without scanning or deleting code data. */
    ProjectWorkspace registerRoot(String projectKey, Path absoluteRoot);
}
