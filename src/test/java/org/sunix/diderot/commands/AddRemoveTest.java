package org.sunix.diderot.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sunix.diderot.testutil.Git;

import picocli.CommandLine;

/**
 * `add` and `remove` driven through the real command line, against a local fixture repository, so
 * the manifest editing, the single-skill pinning and the rollback are all exercised end to end.
 */
class AddRemoveTest {

    @TempDir
    Path tmp;

    Path upstream;
    Path project;
    StringWriter out;
    StringWriter err;

    @BeforeEach
    void setUp() throws Exception {
        upstream = tmp.resolve("upstream");
        Files.createDirectories(upstream.resolve("skills/documentation/making-of"));
        Files.writeString(upstream.resolve("skills/documentation/making-of/SKILL.md"),
                "---\nname: making-of\n---\nInstructions.\n");
        Files.createDirectories(upstream.resolve("skills/webapp/star-button"));
        Files.writeString(upstream.resolve("skills/webapp/star-button/SKILL.md"),
                "---\nname: star-button\n---\nInstructions.\n");
        Files.createDirectories(upstream.resolve("not-a-skill"));
        Files.writeString(upstream.resolve("not-a-skill/README.md"), "no SKILL.md here\n");
        Git.run(upstream, "git", "init", "-q", "-b", "main");
        Git.run(upstream, "git", "add", "-A");
        Git.run(upstream, "git", "commit", "-q", "-m", "first");

        project = tmp.resolve("project");
        Files.createDirectories(project);
    }

    private int run(Object command, String... args) {
        out = new StringWriter();
        err = new StringWriter();
        return new CommandLine(command)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }

    private String source(String path) {
        return "git+file://" + upstream + "#" + path;
    }

    @Test
    void addWritesTheManifestPinsTheLockAndInfersTheName() throws Exception {
        assertEquals(0, run(new AddCommand(),
                source("skills/documentation/making-of"), "--version", "main", "-C", project.toString()),
                err.toString());

        assertEquals("""
                skills:
                  - name: making-of
                    source: %s
                    version: main
                """.formatted(source("skills/documentation/making-of")),
                Files.readString(project.resolve("diderot.yaml")),
                "the name came from the last path segment, no --name needed");

        String lock = Files.readString(project.resolve("diderot.lock"));
        assertTrue(lock.contains("name: making-of"), lock);
        assertTrue(lock.contains("digest: tree:"), "pinned by content digest: " + lock);
        assertTrue(out.toString().contains("run `diderot install`"), out.toString());
    }

    @Test
    void aSecondAddLeavesTheFirstPinUntouched() throws Exception {
        run(new AddCommand(), source("skills/documentation/making-of"), "--version", "main",
                "-C", project.toString());
        String firstResolved = Files.readString(project.resolve("diderot.lock")).lines()
                .filter(l -> l.trim().startsWith("resolved:")).findFirst().orElseThrow().trim();

        assertEquals(0, run(new AddCommand(), source("skills/webapp/star-button"), "--version", "main",
                "-C", project.toString()), err.toString());

        String lock = Files.readString(project.resolve("diderot.lock"));
        assertTrue(lock.contains("name: making-of") && lock.contains("name: star-button"), lock);
        assertTrue(lock.contains(firstResolved),
                "the first skill is still pinned to exactly the commit it was pinned to");
    }

    /** Declaring something that cannot be resolved must not leave the declaration behind. */
    @Test
    void aFailedResolutionRollsTheManifestBack() throws Exception {
        Files.writeString(project.resolve("diderot.yaml"), """
                # keep this comment
                skills:
                  - name: making-of
                    source: %s
                    version: main
                """.formatted(source("skills/documentation/making-of")));
        String before = Files.readString(project.resolve("diderot.yaml"));

        assertEquals(1, run(new AddCommand(), source("not-a-skill"), "--version", "main",
                "-C", project.toString()));
        assertTrue(err.toString().contains("SKILL.md"), err.toString());
        assertEquals(before, Files.readString(project.resolve("diderot.yaml")),
                "the manifest is byte-for-byte what it was, comment included");
    }

    @Test
    void addingTheSameNameTwiceIsRefusedWithTheSourceItAlreadyHas() {
        run(new AddCommand(), source("skills/documentation/making-of"), "--version", "main",
                "-C", project.toString());
        assertEquals(1, run(new AddCommand(), source("skills/webapp/star-button"),
                "--name", "making-of", "--version", "main", "-C", project.toString()));
        assertTrue(err.toString().contains("already declared"), err.toString());
        assertTrue(err.toString().contains("skills/documentation/making-of"),
                "the error names the source it already points at: " + err);
    }

    @Test
    void removeUndeclaresUnpinsAndDeletesTheInstalledCopy() throws Exception {
        run(new AddCommand(), source("skills/documentation/making-of"), "--version", "main",
                "-C", project.toString());
        assertEquals(0, run(new InstallCommand(), "-C", project.toString()), err.toString());
        Path installed = project.resolve(".claude/skills/making-of");
        assertTrue(Files.isDirectory(installed));

        assertEquals(0, run(new RemoveCommand(), "making-of", "-C", project.toString()), err.toString());

        assertFalse(Files.exists(installed), "the installed copy is gone");
        assertFalse(Files.readString(project.resolve("diderot.yaml")).contains("making-of"));
        assertFalse(Files.readString(project.resolve("diderot.lock")).contains("making-of"));
        assertTrue(out.toString().contains("deleted .claude/skills/making-of"), out.toString());
    }

    @Test
    void keepInstalledUndeclaresButLeavesTheFilesAlone() throws Exception {
        run(new AddCommand(), source("skills/documentation/making-of"), "--version", "main",
                "-C", project.toString());
        run(new InstallCommand(), "-C", project.toString());

        assertEquals(0, run(new RemoveCommand(), "making-of", "--keep-installed",
                "-C", project.toString()), err.toString());
        assertTrue(Files.isDirectory(project.resolve(".claude/skills/making-of")));
        assertFalse(Files.readString(project.resolve("diderot.yaml")).contains("making-of"));
    }

    @Test
    void removingSomethingUnknownFailsInsteadOfPretending() throws Exception {
        Files.writeString(project.resolve("diderot.yaml"), "skills: []\n");
        assertEquals(1, run(new RemoveCommand(), "nope", "-C", project.toString()));
        assertTrue(err.toString().contains("Nothing to remove"), err.toString());
    }
}
