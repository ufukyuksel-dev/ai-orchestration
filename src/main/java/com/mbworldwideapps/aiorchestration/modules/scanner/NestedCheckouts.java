package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A directory below the scan root that has its own {@code .git} (a nested repository or a git worktree, e.g.
 * {@code .claude/worktrees/agent-*}) is another checkout: scanning it would duplicate every symbol of this one
 * and make exact locators ambiguous.
 */
final class NestedCheckouts {

    private NestedCheckouts() {
    }

    static boolean inside(Path root, Path path) {
        for (Path dir = Files.isDirectory(path) ? path : path.getParent();
                dir != null && dir.startsWith(root) && !dir.equals(root); dir = dir.getParent()) {
            if (Files.exists(dir.resolve(".git"))) return true;
        }
        return false;
    }
}
