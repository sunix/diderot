package org.sunix.diderot.oci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.sunix.diderot.core.GitTreeHasher;
import org.sunix.diderot.core.LockFile;
import org.sunix.diderot.core.Workspace;
import org.sunix.diderot.git.GitCli;
import org.sunix.diderot.testutil.Git;

/**
 * Full OCI cycle against a real registry (registry:2 in a container): push a skill directory,
 * resolve + lock it from a manifest, install it, tamper with it, repair it. Skipped when no
 * container runtime is available.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OciRoundTripTest {

    static String containerId;
    static String registryHostPort;

    @TempDir
    Path tmp;

    @BeforeAll
    void startRegistry() throws Exception {
        try {
            Git.run(Path.of("."), "docker", "version");
        } catch (Exception e) {
            assumeTrue(false, "docker not available: " + e.getMessage());
        }
        containerId = Git.run(Path.of("."), "docker", "run", "-d", "--rm",
                "-p", "127.0.0.1:0:5000", "registry:2").trim();
        String portLine = Git.run(Path.of("."), "docker", "port", containerId, "5000/tcp").trim();
        registryHostPort = portLine.lines().findFirst().orElseThrow(); // e.g. 127.0.0.1:32768
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest ping = HttpRequest.newBuilder(URI.create("http://" + registryHostPort + "/v2/")).build();
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            try {
                http.send(ping, HttpResponse.BodyHandlers.discarding());
                break;
            } catch (Exception e) {
                if (System.currentTimeMillis() > deadline) {
                    throw new IllegalStateException("registry:2 did not become ready", e);
                }
                Thread.sleep(300);
            }
        }
    }

    @AfterAll
    void stopRegistry() throws Exception {
        if (containerId != null) {
            Git.run(Path.of("."), "docker", "rm", "-f", containerId);
        }
    }

    @Test
    void pushUpdateInstallDriftRepair() throws Exception {
        // The skill to publish.
        Path skillDir = tmp.resolve("making-of");
        Files.createDirectories(skillDir.resolve("templates"));
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nname: making-of\n---\nInstructions.\n");
        Files.writeString(skillDir.resolve("templates/MAKING-OF.md"), "# template\n");
        String sourceTreeDigest = "tree:" + GitTreeHasher.treeSha(skillDir);

        OrasClient oras = new OrasClient(tmp.resolve("oci-cache"));
        String repository = registryHostPort + "/skills/making-of";
        String pushedDigest = oras.push(skillDir, repository + ":v1");
        assertTrue(pushedDigest.startsWith("sha256:"));

        // A consumer project declaring that OCI source.
        Path project = tmp.resolve("project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: making-of
                    source: oci://%s
                    version: v1
                targets: [claude]
                """.formatted(repository));
        StringWriter output = new StringWriter();
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache")),
                oras, new PrintWriter(output, true));

        LockFile lock = workspace.update();
        assertEquals(pushedDigest, lock.skills.get(0).resolved,
                "the lock pins the exact manifest digest the push produced");
        assertEquals(sourceTreeDigest, lock.skills.get(0).digest,
                "the locked content digest equals the source directory's tree hash: "
                        + "what was pushed is byte-for-byte what got locked");

        workspace.install(null);
        Path installed = project.resolve(".claude/skills/making-of");
        assertTrue(Files.isRegularFile(installed.resolve("SKILL.md")));
        assertTrue(Files.isRegularFile(installed.resolve("templates/MAKING-OF.md")));
        assertEquals(0, workspace.status(null));

        Files.writeString(installed.resolve("SKILL.md"), "tampered\n");
        assertEquals(1, workspace.status(null), "drift in an OCI-sourced skill is detected");
        workspace.install(null);
        assertEquals(0, workspace.status(null), "reinstall from the digest-pinned cache repairs it");
    }

    @Test
    void updateRefusesAnArtifactWithoutSkillMd() throws Exception {
        Path notASkill = tmp.resolve("not-a-skill");
        Files.createDirectories(notASkill);
        Files.writeString(notASkill.resolve("README.md"), "no SKILL.md here\n");
        OrasClient oras = new OrasClient(tmp.resolve("oci-cache-2"));
        String repository = registryHostPort + "/skills/not-a-skill";
        oras.push(notASkill, repository + ":v1");

        Path project = tmp.resolve("project-2");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: not-a-skill
                    source: oci://%s
                    version: v1
                """.formatted(repository));
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache-2")),
                oras, new PrintWriter(new StringWriter(), true));

        var e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, workspace::update);
        assertTrue(e.getMessage().contains("SKILL.md"));
    }

    /**
     * Ranges against a real registry. Every release carries different content, so the assertions
     * prove the right *release* was installed and not merely that some digest matched - and the
     * expected digest is whatever the push of 1.2.3 returned, never a value written down here.
     */
    @Test
    void aCaretRangeResolvesToTheHighestPublishedRelease() throws Exception {
        OrasClient oras = new OrasClient(tmp.resolve("oci-cache-3"));
        String repository = registryHostPort + "/skills/ranged";
        Map<String, String> pushed = new LinkedHashMap<>();
        for (String version : List.of("1.0.0", "1.1.0", "1.2.3", "2.0.0", "1.3.0-rc.1")) {
            pushed.put(version, publish(oras, repository, version, "Release " + version + ".\n"));
        }
        // The moving tags a registry always carries beside its releases.
        publish(oras, repository, "latest", "Whatever is newest.\n");
        publish(oras, repository, "main", "Whatever is on main.\n");

        Path project = tmp.resolve("project-3");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: ranged
                    source: oci://%s
                    version: "^1.0.0"
                targets: [claude]
                """.formatted(repository));
        StringWriter output = new StringWriter();
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache-3")),
                oras, new PrintWriter(output, true));

        LockFile lock = workspace.update();

        assertEquals("1.2.3", lock.skills.get(0).tag,
                "^1.0.0 excludes 2.0.0 as a major bump, 1.3.0-rc.1 as a pre-release, "
                        + "and latest/main as unparseable");
        assertEquals(pushed.get("1.2.3"), lock.skills.get(0).resolved,
                "the lock pins the exact manifest digest that publishing 1.2.3 produced");
        assertTrue(output.toString().contains(":1.2.3@"),
                "update names the tag it chose, since a range makes the answer invisible otherwise");

        workspace.install(null);
        assertEquals("---\nname: ranged\nversion: 1.2.3\n---\nRelease 1.2.3.\n",
                Files.readString(project.resolve(".claude/skills/ranged/SKILL.md")),
                "the content on disk is 1.2.3's, so the range resolved to a release and not just a digest");
        assertEquals(0, workspace.status(null));
    }

    /** A range nothing satisfies must say what the repository does publish, not just fail. */
    @Test
    void anUnsatisfiableRangeListsThePublishedVersions() throws Exception {
        OrasClient oras = new OrasClient(tmp.resolve("oci-cache-4"));
        String repository = registryHostPort + "/skills/narrow";
        publish(oras, repository, "1.0.0", "Release 1.0.0.\n");
        publish(oras, repository, "2.0.0", "Release 2.0.0.\n");
        publish(oras, repository, "latest", "Newest.\n");

        Path project = tmp.resolve("project-4");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: narrow
                    source: oci://%s
                    version: "^9.0.0"
                """.formatted(repository));
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache-4")),
                oras, new PrintWriter(new StringWriter(), true));

        var e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, workspace::update);
        assertTrue(e.getMessage().contains("^9.0.0"), "the range that failed is named: " + e.getMessage());
        assertTrue(e.getMessage().contains("2.0.0") && e.getMessage().contains("1.0.0"),
                "and so is what the repository actually publishes: " + e.getMessage());
    }

    /** A literal tag stays literal: it must not be reinterpreted as a range. */
    @Test
    void latestResolvesTheMovingTagRatherThanTheNewestRelease() throws Exception {
        OrasClient oras = new OrasClient(tmp.resolve("oci-cache-5"));
        String repository = registryHostPort + "/skills/moving";
        publish(oras, repository, "1.0.0", "Release 1.0.0.\n");
        String movingDigest = publish(oras, repository, "latest", "The moving one.\n");
        publish(oras, repository, "2.0.0", "Release 2.0.0.\n");

        Path project = tmp.resolve("project-5");
        Files.createDirectories(project);
        Files.writeString(project.resolve("diderot.yaml"), """
                skills:
                  - name: moving
                    source: oci://%s
                    version: latest
                """.formatted(repository));
        Workspace workspace = new Workspace(project, new GitCli(tmp.resolve("git-cache-5")),
                oras, new PrintWriter(new StringWriter(), true));

        LockFile lock = workspace.update();
        assertEquals(movingDigest, lock.skills.get(0).resolved,
                "`latest` is the tag named latest - not 2.0.0, which is what a range parser "
                        + "reading it as >=0.0.0 would have picked");
    }

    /**
     * `add` with no `--version` has to write the default the *source* uses, not the model's. Writing
     * git's `HEAD` into an oci:// entry resolves fine and reads as the wrong vocabulary.
     */
    @Test
    void addWithNoVersionWritesLatestForARegistrySource() throws Exception {
        OrasClient oras = new OrasClient(tmp.resolve("oci-cache-6"));
        String repository = registryHostPort + "/skills/defaulted";
        publish(oras, repository, "latest", "Whatever is newest.\n");

        Path project = tmp.resolve("project-6");
        Files.createDirectories(project);
        StringWriter errors = new StringWriter();
        int code = new picocli.CommandLine(new org.sunix.diderot.commands.AddCommand())
                .setOut(new PrintWriter(new StringWriter(), true))
                .setErr(new PrintWriter(errors, true))
                .execute("oci://" + repository, "-C", project.toString());

        assertEquals(0, code, errors.toString());
        assertTrue(Files.readString(project.resolve("diderot.yaml")).contains("version: latest"),
                "a registry's moving default is `latest`: "
                        + Files.readString(project.resolve("diderot.yaml")));
    }

    /** Publishes one version of a throwaway skill and returns the manifest digest it produced. */
    private String publish(OrasClient oras, String repository, String tag, String body) throws Exception {
        String skillName = repository.substring(repository.lastIndexOf('/') + 1);
        Path dir = tmp.resolve("src-" + skillName + "-" + tag);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("SKILL.md"),
                "---\nname: " + skillName + "\nversion: " + tag + "\n---\n" + body);
        return oras.push(dir, repository + ":" + tag);
    }
}
