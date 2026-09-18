package org.sunix.diderot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sunix.diderot.testutil.Git;

/**
 * The content digest a signature attests to. The first test checks it against an independent
 * reimplementation in shell rather than against a number typed into this file — the same
 * oracle-before-fixture rule part one used against real git, because a hash test that asserts its
 * own output proves only that the code still does what it did yesterday.
 */
class ContentDigestTest {

    @TempDir
    Path tmp;

    @Test
    void matchesAnIndependentShellImplementation() throws Exception {
        Path skill = tmp.resolve("skill");
        Files.createDirectories(skill.resolve("templates"));
        Files.writeString(skill.resolve("SKILL.md"), "---\nname: making-of\n---\nInstructions.\n");
        Files.writeString(skill.resolve("templates/MAKING-OF.md"), "# template\n");
        Files.writeString(skill.resolve("README.md"), "read me\n");

        // sha256 of: the format line, then "<mode> <sha256> <path>\n" per file, sorted by path.
        String oracle = Git.run(skill, "sh", "-c",
                "{ printf 'diderot-content-v1\\n'; "
                        + "find . -type f | sed 's|^\\./||' | LC_ALL=C sort | "
                        + "while read -r f; do printf '100644 %s %s\\n' "
                        + "\"$(sha256sum \"$f\" | cut -d' ' -f1)\" \"$f\"; done; } | "
                        + "sha256sum | cut -d' ' -f1").trim();

        assertEquals("sha256:" + oracle, ContentDigest.sha256Of(skill));
    }

    @Test
    void oneChangedByteChangesTheDigest() throws Exception {
        Path skill = tmp.resolve("byte");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), "collect the environment\n");
        String before = ContentDigest.sha256Of(skill);

        Files.writeString(skill.resolve("SKILL.md"), "collect the environment.\n");

        assertNotEquals(before, ContentDigest.sha256Of(skill));
    }

    @Test
    void theExecutableBitIsPartOfTheContent() throws Exception {
        Path skill = tmp.resolve("mode");
        Files.createDirectories(skill);
        Path script = skill.resolve("run.sh");
        Files.writeString(script, "#!/bin/sh\necho hello\n");
        String beforeChmod = ContentDigest.sha256Of(skill);

        Files.setPosixFilePermissions(script, Set.copyOf(
                java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")));

        assertNotEquals(beforeChmod, ContentDigest.sha256Of(skill),
                "a file that becomes executable is different content, exactly as git sees it");
    }

    @Test
    void movingAFileChangesTheDigestEvenWhenTheBytesAreIdentical() throws Exception {
        Path skill = tmp.resolve("layout");
        Files.createDirectories(skill.resolve("templates"));
        Files.writeString(skill.resolve("SKILL.md"), "same bytes\n");
        String flat = ContentDigest.sha256Of(skill);

        Files.move(skill.resolve("SKILL.md"), skill.resolve("templates/SKILL.md"));

        assertNotEquals(flat, ContentDigest.sha256Of(skill), "paths are part of what is signed");
    }

    @Test
    void aSymlinkIsHashedByItsTargetRatherThanFollowed() throws Exception {
        Path skill = tmp.resolve("links");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), "instructions\n");
        Files.createSymbolicLink(skill.resolve("current"), Path.of("SKILL.md"));
        String pointingHere = ContentDigest.sha256Of(skill);

        Files.delete(skill.resolve("current"));
        Files.createSymbolicLink(skill.resolve("current"), Path.of("/etc/passwd"));

        assertNotEquals(pointingHere, ContentDigest.sha256Of(skill),
                "a link that starts pointing somewhere else is a change, whatever it points at");
    }

    @Test
    void agreesWithTheTreeHasherOnWhichFilesCount() throws Exception {
        Path withGit = tmp.resolve("with-git");
        Files.createDirectories(withGit.resolve(".git"));
        Files.writeString(withGit.resolve(".git/HEAD"), "ref: refs/heads/main\n");
        Files.writeString(withGit.resolve("SKILL.md"), "instructions\n");

        Path withoutGit = tmp.resolve("without-git");
        Files.createDirectories(withoutGit);
        Files.writeString(withoutGit.resolve("SKILL.md"), "instructions\n");

        assertEquals(ContentDigest.sha256Of(withoutGit), ContentDigest.sha256Of(withGit),
                ".git is skipped, so a checkout and an export of the same skill sign the same");
        assertEquals(GitTreeHasher.treeSha(withoutGit), GitTreeHasher.treeSha(withGit),
                "and the tree hasher agrees, which is what keeps the two identities describing "
                        + "the same set of files");
    }
}
