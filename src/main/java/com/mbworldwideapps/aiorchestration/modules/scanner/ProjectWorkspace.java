package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * Server-verified binding between a logical project and its canonical Git worktree.
 */
public record ProjectWorkspace(
        String projectKey,
        Path repositoryRoot,
        String repositoryFingerprint,
        List<Path> nestedProjectRoots) {

    public ProjectWorkspace(String projectKey, Path repositoryRoot, String repositoryFingerprint) {
        this(projectKey, repositoryRoot, repositoryFingerprint, List.of());
    }

    public ProjectWorkspace {
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException("projectKey must not be blank");
        }
        if (repositoryRoot == null || !repositoryRoot.isAbsolute()) {
            throw new IllegalArgumentException("repositoryRoot must be absolute");
        }
        if (repositoryFingerprint == null || repositoryFingerprint.isBlank()) {
            throw new IllegalArgumentException("repositoryFingerprint must not be blank");
        }
        projectKey = projectKey.trim();
        Path normalizedRepositoryRoot = repositoryRoot.normalize();
        try {
            if (!Files.isDirectory(normalizedRepositoryRoot)
                    || !normalizedRepositoryRoot.equals(normalizedRepositoryRoot.toRealPath())) {
                throw new IllegalArgumentException("repositoryRoot must be a canonical directory");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("repositoryRoot must be a canonical directory", exception);
        }
        repositoryRoot = normalizedRepositoryRoot;
        repositoryFingerprint = repositoryFingerprint.trim();
        nestedProjectRoots = nestedProjectRoots == null ? List.of() : nestedProjectRoots.stream()
                .map(Path::normalize)
                .peek(path -> {
                    if (!path.isAbsolute() || path.equals(normalizedRepositoryRoot)
                            || !path.startsWith(normalizedRepositoryRoot)) {
                        throw new IllegalArgumentException("nestedProjectRoots must be canonical descendants");
                    }
                })
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Resolves a repository-relative target without allowing lexical or symlink escape.
     */
    public Path resolveInside(String repositoryRelativePath) {
        if (repositoryRelativePath == null || repositoryRelativePath.isBlank()) {
            throw new ProjectWorkspaceResolutionException("root_escape:blank_path");
        }
        Path relative;
        try {
            relative = Path.of(repositoryRelativePath.trim()).normalize();
        } catch (RuntimeException exception) {
            throw new ProjectWorkspaceResolutionException("root_escape:invalid_path", exception);
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new ProjectWorkspaceResolutionException("root_escape:" + repositoryRelativePath);
        }
        Path candidate = repositoryRoot.resolve(relative).normalize();
        if (!candidate.startsWith(repositoryRoot)) {
            throw new ProjectWorkspaceResolutionException("root_escape:" + repositoryRelativePath);
        }
        rejectNestedProject(candidate, repositoryRelativePath);
        try {
            Path resolved = repositoryRoot;
            for (Path segment : relative) {
                Path next = resolved.resolve(segment).normalize();
                if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
                    // Resolve every existing segment independently. NOFOLLOW_LINKS makes a
                    // dangling symlink visible here; toRealPath then rejects it instead of
                    // treating it as an ordinary not-yet-created path under the worktree.
                    next = next.toRealPath();
                }
                if (!next.startsWith(repositoryRoot)) {
                    throw new ProjectWorkspaceResolutionException("root_escape:" + repositoryRelativePath);
                }
                rejectNestedProject(next, repositoryRelativePath);
                resolved = next;
            }
            return resolved;
        } catch (IOException exception) {
            throw new ProjectWorkspaceResolutionException("root_escape:" + repositoryRelativePath, exception);
        }
    }

    private void rejectNestedProject(Path candidate, String repositoryRelativePath) {
        if (nestedProjectRoots.stream().anyMatch(candidate::startsWith)) {
            throw new ProjectWorkspaceResolutionException("nested_project_escape:" + repositoryRelativePath);
        }
    }
}
