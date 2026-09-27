package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class JdbcProjectWorkspaceResolver implements ProjectWorkspaceResolver {

    private static final String FINGERPRINT_VERSION = "project-workspace-v1";

    private final JdbcTemplate jdbcTemplate;

    JdbcProjectWorkspaceResolver(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectWorkspace resolve(String projectKey) {
        String normalizedProjectKey = require(projectKey, "projectKey");
        List<RootBinding> bindings = allBindings();
        RootBinding requested = bindings.stream()
                .filter(binding -> normalizedProjectKey.equals(binding.projectKey()))
                .findFirst()
                .orElseThrow(() -> failure("unknown_project", normalizedProjectKey));
        ProjectWorkspace workspace = canonicalWorkspace(requested);
        List<ProjectWorkspace> valid = bindings.stream()
                .map(this::canonicalWorkspaceIfValid)
                .flatMap(java.util.Optional::stream)
                .toList();
        return withNestedBoundaries(workspace, valid);
    }

    private List<RootBinding> allBindings() {
        return jdbcTemplate.query("""
                SELECT project_key, root_path
                FROM scanner_project_roots
                ORDER BY project_key
                """, (rs, rowNum) -> new RootBinding(rs.getString("project_key"), rs.getString("root_path")),
                new Object[0]);
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectWorkspace resolve(String projectKey, String expectedRepositoryFingerprint) {
        String expected = require(expectedRepositoryFingerprint, "expectedRepositoryFingerprint");
        ProjectWorkspace workspace = resolve(projectKey);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                workspace.repositoryFingerprint().getBytes(StandardCharsets.US_ASCII))) {
            throw failure("fingerprint_mismatch", workspace.projectKey());
        }
        return workspace;
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectWorkspace resolveByPath(Path path) {
        Path requested = canonicalizeRequestedPath(path);
        List<RootBinding> bindings = allBindings();
        List<ProjectWorkspace> valid = new ArrayList<>();
        for (RootBinding binding : bindings) {
            try {
                ProjectWorkspace workspace = canonicalWorkspace(binding);
                valid.add(workspace);
            } catch (ProjectWorkspaceResolutionException exception) {
                // A legacy aggregate binding must not poison an independently
                // registered Git repository nested below it. Keep failing closed
                // when callers address the broken binding itself.
                if (lexicallyEquals(binding.rootPath(), path)) {
                    throw exception;
                }
            }
        }
        List<ProjectWorkspace> candidates = valid.stream()
                .filter(workspace -> requested.startsWith(workspace.repositoryRoot()))
                .toList();
        if (candidates.isEmpty()) {
            long registeredDescendants = valid.stream()
                    .map(ProjectWorkspace::repositoryRoot)
                    .filter(root -> root.startsWith(requested))
                    .count();
            if (registeredDescendants > 1) {
                throw failure("ambiguous_project_container", requested.toString());
            }
            throw failure("unknown_project_path", requested.toString());
        }
        int deepest = candidates.stream()
                .map(ProjectWorkspace::repositoryRoot)
                .mapToInt(Path::getNameCount)
                .max()
                .orElseThrow();
        List<ProjectWorkspace> best = candidates.stream()
                .filter(candidate -> candidate.repositoryRoot().getNameCount() == deepest)
                .sorted(Comparator.comparing(ProjectWorkspace::projectKey))
                .toList();
        // Legacy keys may point to the same checkout. Explicit key resolution remains
        // available; path-only resolution chooses a stable existing key without merging data.
        return withNestedBoundaries(best.getFirst(), valid);
    }

    @Override
    @Transactional
    public ProjectWorkspace registerRoot(String projectKey, Path absoluteRoot) {
        String key = require(projectKey, "projectKey");
        if (absoluteRoot == null || !absoluteRoot.isAbsolute()) {
            throw failure("absolute_root_required", key);
        }
        Path root = canonicalizeRequestedPath(absoluteRoot);
        canonicalWorkspace(new RootBinding(key, root.toString()));
        // A project key identifies the logical project; its current checkout may move.
        jdbcTemplate.execute("LOCK TABLE scanner_project_roots IN SHARE ROW EXCLUSIVE MODE");
        jdbcTemplate.update("""
                INSERT INTO scanner_project_roots (project_key, root_path) VALUES (?, ?)
                ON CONFLICT (project_key) DO UPDATE
                SET root_path = EXCLUDED.root_path, last_seen_at = now()
                """, key, root.toString());
        return resolve(key);
    }

    private ProjectWorkspace canonicalWorkspace(RootBinding binding) {
        Path root;
        try {
            Path configured = Path.of(require(binding.rootPath(), "rootPath"));
            if (!configured.isAbsolute()) {
                throw failure("broken_root_binding", binding.projectKey());
            }
            if (!Files.isDirectory(configured)) {
                throw failure("broken_root_binding", binding.projectKey());
            }
            root = configured.toRealPath();
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof ProjectWorkspaceResolutionException resolutionException) {
                throw resolutionException;
            }
            throw failure("broken_root_binding", binding.projectKey(), exception);
        }
        Path gitMetadata = gitMetadataDirectory(root, binding.projectKey());
        String fingerprint = sha256(FINGERPRINT_VERSION + "\0" + root + "\0"
                + physicalKey(root, binding.projectKey()) + "\0" + gitMetadata + "\0"
                + physicalKey(gitMetadata, binding.projectKey()));
        return new ProjectWorkspace(require(binding.projectKey(), "projectKey"), root, fingerprint);
    }

    private java.util.Optional<ProjectWorkspace> canonicalWorkspaceIfValid(RootBinding binding) {
        try {
            return java.util.Optional.of(canonicalWorkspace(binding));
        } catch (ProjectWorkspaceResolutionException ignored) {
            return java.util.Optional.empty();
        }
    }

    private static ProjectWorkspace withNestedBoundaries(ProjectWorkspace workspace,
            List<ProjectWorkspace> allWorkspaces) {
        List<Path> nestedRoots = allWorkspaces.stream()
                .map(ProjectWorkspace::repositoryRoot)
                .filter(root -> !root.equals(workspace.repositoryRoot()))
                .filter(root -> root.startsWith(workspace.repositoryRoot()))
                .distinct()
                .toList();
        return new ProjectWorkspace(workspace.projectKey(), workspace.repositoryRoot(),
                workspace.repositoryFingerprint(), nestedRoots);
    }

    private static Path gitMetadataDirectory(Path root, String projectKey) {
        Path marker = root.resolve(".git");
        if (!Files.isDirectory(marker) && !Files.isRegularFile(marker)) {
            throw failure("not_git_worktree_root", projectKey);
        }
        try {
            return marker.toRealPath();
        } catch (IOException exception) {
            throw failure("not_git_worktree_root", projectKey, exception);
        }
    }

    private static Path canonicalizeRequestedPath(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            throw failure("unresolvable_project_path", absolute.toString());
        }
        try {
            Path canonicalExisting = existing.toRealPath();
            return canonicalExisting.resolve(existing.relativize(absolute)).normalize();
        } catch (IOException exception) {
            throw failure("unresolvable_project_path", absolute.toString(), exception);
        }
    }

    private static String physicalKey(Path path, String projectKey) {
        try {
            Object key = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            // Some filesystems do not expose inode/file keys. Marker presence is enough
            // for resolution; retain a stable path identity on those filesystems.
            return key == null ? path.toString() : key.toString();
        } catch (IOException exception) {
            throw failure("filesystem_identity_unavailable", projectKey, exception);
        }
    }

    private static boolean lexicallyEquals(String rootPath, Path requestedPath) {
        try {
            Path root = Path.of(rootPath).toAbsolutePath().normalize();
            Path requested = requestedPath.toAbsolutePath().normalize();
            return requested.equals(root);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ProjectWorkspaceResolutionException failure(String reason, String subject) {
        return new ProjectWorkspaceResolutionException(reason + ":" + subject);
    }

    private static ProjectWorkspaceResolutionException failure(String reason, String subject, Throwable cause) {
        return new ProjectWorkspaceResolutionException(reason + ":" + subject, cause);
    }

    private record RootBinding(String projectKey, String rootPath) {
    }
}
