package com.mbworldwideapps.aiorchestration.modules.scanner;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

class NestedCheckoutsTest {

    @Test
    void filesOfAWorktreeOrNestedRepositoryBelowTheRootAreOutside(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve(".git"));
        Path own = Files.createDirectories(root.resolve("src/main/java")).resolve("A.java");
        Path worktree = Files.createDirectories(root.resolve(".claude/worktrees/agent-1"));
        Files.writeString(worktree.resolve(".git"), "gitdir: ../../../.git/worktrees/agent-1\n");
        Path copy = Files.createDirectories(worktree.resolve("src/main/java")).resolve("A.java");
        Path nested = Files.createDirectories(root.resolve("vendor/lib"));
        Files.createDirectory(nested.resolve(".git"));
        Files.writeString(own, "class A {}");
        Files.writeString(copy, "class A {}");

        assertThat(NestedCheckouts.inside(root, own)).isFalse();
        assertThat(NestedCheckouts.inside(root, copy)).isTrue();
        assertThat(NestedCheckouts.inside(root, worktree)).isTrue();
        assertThat(NestedCheckouts.inside(root, nested.resolve("B.java"))).isTrue();
    }
}
