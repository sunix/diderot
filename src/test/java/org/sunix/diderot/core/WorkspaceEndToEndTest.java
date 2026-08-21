package org.sunix.diderot.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.testutil.Git;

/**
 * Full update → install → status cycle against a local fixture git repository (git+file:// source),
 * shaped like ai-skills: skills nested under a category directory. No network involved.
 */
class WorkspaceEndToEndTest {

    @TempDir
    Path tmp;

    Path upstream;
    Path project;
    Workspace workspace;
    StringWriter output;

    @BeforeEach
    void setUp() throws Exception {
        // Fixture "ai-skills": a repo with a skill under a category directory.
        upstream = tmp.resolve("upstream");
        Files.createDirectories(upstream.resolve("skills/documentation/making-of/templates"));
        Files.writeString(upstream.resolve("skills/documentation/making-of/SKILL.md"),
                "---\nname: making-of\ndescription: journal\n---\nInstructions.\n");
        Files.writeString(upstream.resolve("skills/documentation/making-of/templates/MAKING-OF.md"),
                "# template\n");
        Files.writeString(upstream.resolve("README.md"), "not part of the skill\n");
        Git.run(upstream, "git", "init", "-q", "-b", "main");
        Git.run(upstream, "git", "add", "-A");
        Git.run(upstream, "git", "commit", "-q", "-m", "first");

        // Consumer project with a manifest pointing at the fixture.
        project = tmp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: making-of
                    source: git+file://%s#skills/documentation/making-of
                    version: main
                targets: [claude]
                """.formatted(upstream));

        output = new StringWriter();
        workspace = new Workspace(project, new GitCli(tmp.resolve("cache")), new PrintWriter(output, true));
    }

    @Test
    void updateInstallStatusRoundTrip() throws Exception {
        LockFile lock = workspace.update();

        assertEquals(1, lock.skills.size());
        assertEquals("making-of", lock.skills.get(0).name);
        assertEquals(40, lock.skills.get(0).resolved.length(), "resolved must be a full commit SHA");
        assertTrue(lock.skills.get(0).digest.startsWith("tree:"), "digest must be a content digest");
        assertTrue(Files.isRegularFile(project.resolve("diderot.lock")));

        workspace.install(null);
        Path installed = project.resolve(".claude/skills/making-of");
        assertTrue(Files.isRegularFile(installed.resolve("SKILL.md")),
                "the skill directory content is installed, not the whole repo");
        assertTrue(Files.isRegularFile(installed.resolve("templates/MAKING-OF.md")));
        assertTrue(Files.notExists(installed.resolve("README.md")),
                "files outside the skill path must not leak into the install");

        assertEquals(0, workspace.status(null), "freshly installed skills report no drift");
    }

    @Test
    void statusDetectsDriftAndInstallRepairsIt() throws Exception {
        workspace.update();
        workspace.install(null);
        Path skillMd = project.resolve(".claude/skills/making-of/SKILL.md");
        Files.writeString(skillMd, "tampered locally\n");

        assertEquals(1, workspace.status(null), "a modified file must be reported as drift");
        assertTrue(output.toString().contains("DRIFTED"));

        workspace.install(null);
        assertEquals(0, workspace.status(null), "reinstalling from the lock repairs the drift");
    }

    @Test
    void lockPinsTheCommitEvenWhenTheBranchMovesOn() throws Exception {
        LockFile first = workspace.update();

        // Upstream moves: the branch now points to a different commit.
        Files.writeString(upstream.resolve("skills/documentation/making-of/SKILL.md"),
                "---\nname: making-of\n---\nv2 instructions\n");
        Git.run(upstream, "git", "add", "-A");
        Git.run(upstream, "git", "commit", "-q", "-m", "second");

        // install (no update) must still produce the first commit's bytes.
        workspace.install(null);
        String installedContent = Files.readString(project.resolve(".claude/skills/making-of/SKILL.md"));
        assertTrue(installedContent.contains("Instructions."), "install obeys the lock, not the branch");
        assertEquals(0, workspace.status(null));

        // update re-resolves and moves the lock forward.
        LockFile second = workspace.update();
        assertNotEquals(first.skills.get(0).resolved, second.skills.get(0).resolved);
    }

    @Test
    void updateRefusesASkillWithoutSkillMd() throws Exception {
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: not-a-skill
                    source: git+file://%s#skills/documentation
                    version: main
                """.formatted(upstream));
        var e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, () -> workspace.update());
        assertTrue(e.getMessage().contains("SKILL.md"), "the error names the missing SKILL.md");
    }

    @Test
    void installWithoutLockExplainsWhatToDo() {
        var e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                () -> workspace.install(List.of("claude")));
        assertTrue(e.getMessage().contains("diderot update"));
    }
}
