package org.sunix.diderot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sunix.diderot.testutil.Git;

/**
 * The hasher must reproduce git's own tree SHA byte for byte — verified against the real git
 * binary, not against a fixed expected string, so any divergence from git's object format fails.
 */
class GitTreeHasherTest {

    @TempDir
    Path tmp;

    @Test
    void matchesRealGitTreeSha() throws Exception {
        Path repo = tmp.resolve("repo");
        Files.createDirectories(repo.resolve("sub/deep"));
        Files.writeString(repo.resolve("SKILL.md"), "---\nname: demo\n---\nhello\n");
        Files.writeString(repo.resolve("sub/a.txt"), "aaa\n");
        Files.writeString(repo.resolve("sub/deep/b.txt"), "bbb\n");
        // a name that must sort as "sub/" would (directory sorting rule): "sub-file" < "sub/" in
        // byte order, "sub." < "sub/" too — this catches naive name-only sorting.
        Files.writeString(repo.resolve("sub-file.txt"), "tricky ordering\n");
        Path script = repo.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\necho hi\n");
        script.toFile().setExecutable(true);

        String expected = gitTreeSha(repo);
        assertEquals(expected, GitTreeHasher.treeSha(repo));
    }

    @Test
    void changingOneByteChangesTheSha() throws Exception {
        Path dir = tmp.resolve("drift");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"), "original\n");
        String before = GitTreeHasher.treeSha(dir);
        Files.writeString(dir.resolve("SKILL.md"), "tampered\n");
        String after = GitTreeHasher.treeSha(dir);
        org.junit.jupiter.api.Assertions.assertNotEquals(before, after);
    }

    private static String gitTreeSha(Path dir) throws IOException, InterruptedException {
        Git.run(dir, "git", "init", "-q");
        Git.run(dir, "git", "add", "-A");
        String sha = Git.run(dir, "git", "write-tree").trim();
        return sha;
    }
}
